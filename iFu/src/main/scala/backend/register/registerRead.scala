package iFu.backend

import chisel3._
import chisel3.util._
import iFu.common._

class RegisterRead(
    issueWidth : Int,
    supportedUnitsArray: seq[SupportedFuncUnits],
    numTotalReadPorts: Int,
    numReadPortsArray: Seq[Int],
    numTotalBypassPorts: Int,
    numTotalPredBypassPorts: Int,
    registerWidth: Int
)(implicit p: Parameters) extends CoreModule
{
    val io = IO(new Bundle{
        val iss_valids = Input(Vec(issueWidth,Bool()))
        val iss_uops = Input(Vec(issueWidth,new MicroOp()))

        val rf_read_ports = Flipped(Vec(numTotalReadPorts,new RegisterFileReadPortIO(maxPregSz,registerWidth)))
        val prf_read_ports = Flipped(Vec(issueWidth,new RegisterFileReadPortIO(log2Ceil(ftqSz),1)))

        val bypass = Input(Vec(numTotalBypassPorts,Valid(new ExeUnitResp(registerWidth))))
        val pred_bypass = Input(Vec(numTotalPredBypassPorts,Valid(new ExeUnitResp(1))))

        val exe_reqs = Vec(issueWidth,(new DecoupledIO(new FuncUnitReq(registerWidth))))

        val kill = Input(Bool())
        val brupdate = Input(new BrUpdateInfo())

    })

    val rrdValid = Wire(Vec(issueWidth,Bool()))
    val rrdUops = Wire(Vec(issueWidth,new MicroOp()))

    val exeRegValids = RegInit(VecInit(Seq.fill(issueWidth){false.B}))
    val exeRegUops = Reg(Vec(issueWidth,new MicroOp()))
    val exeRegRs1Data = Reg(Vec(issueWidth,Bits(registerWidth.W)))
    val exeRegRs2Data = Reg(Vec(issueWidth,Bits(registerWidth.W)))
    val exeRegRs3Data = Reg(Vec(issueWidth,Bits(registerWidth.W)))
    val exeRegPredData = Reg(Vec(issueWidth,Bool()))


    for(w <- 0 until issueWidth){
        val rrdDecodeUnit = Module(new RegisterReadDecode(supportedUnitsArray(w)))
        rrdDecodeUnit.io.iss_valid := io.iss_valids(w)
        rrdDecodeUnit.io.iss_uop := io.iss_uops(w)

        rrdValid(w) := RegNext(rrdDecodeUnit.io.rrd_valid &&
                    !IsKilledByBranch(io.brupdate,rrdDecodeUnit.io.rrd_uop))
        rrdUops(w) := RegNext(GetNewUopAndBrMask(rrdDecodeUnit.io.rrd_uop,io.brupdate))

    }


    val rrdRs1Data = Wire(Vec(issueWidth,Bits(registerWidth.W)))
    val rrdRs2Data = Wire(Vec(issueWidth,Bits(registerWidth.W)))
    val rrdRs3Data = Wire(Vec(issueWidth,Bits(registerWidth.W)))
    val rrdPredData  = Wire(Vec(issueWidth,Bool()))

    rrdRs1Data := DontCare
    rrdRs2Data := DontCare
    rrdRs3Data := DontCare
    rrdPredData := DontCare

    io.prf_read_ports := DontCare

    var idx = 0
    for(w <-0 until issueWidth){
        val numReadPorts = numReadPortsArray(w)

        val rs1Addr = io.iss_uops(w).prs1
        val rs2Addr = io.iss_uops(w).prs2
        val rs3Addr = io.iss_uops(w).prs3
        val predAddr = io.iss_uops(w).ppred

        if(numReadPorts > 0 ) io.rf_read_ports(idx+0).addr := rs1_addr
        if(numReadPorts > 1 ) io.rf_read_ports(idx+1).addr := rs2_addr
        if(numReadPorts > 2 ) io.rf_read_ports(idx+2).addr := rs3_addr

        if(enableSFBOPT) io.prf_read_ports(w).addr := pred_addr

        val rrdKill = io.kill || IsKilledByBranch(io.brupdate,rrdUops(w))

        exeRegValids(w) := Mux(rrdKill,false.B,rrdValid(w))
        exeRegUops(w) := Mux(rrdKill,NullMicroOp,rrdUops(w))
        exeRegUops(w).br_mask := GetNewBrMask(io.brupdate,rrdUops(w))

        idx += numReadPorts
    }



    val bypassedRs1Data = Wire(Vec(issueWidth,Bits(registerWidth.W)))
    val bypassedRs2Data = Wire(Vec(issueWidth,Bits(registerWidth.W)))
    val bypassedPredData = Wire(Vec(issueWidth,Bool()))

    bypassedPredData := DontCare

    for(w<- 0 until issueWidth){
        val numReadPorts = numReadPortsArray(w)
        val rs1Cases = Array((false.B, 0.U(registerWidth.W)))
        val rs2Cases = Array((false.B, 0.U(registerWidth.W)))
        val predCases = Array((false.B, 0.U(1.W)))
        
        val prs1 = rrdUops(w).prs1
        val lrs1Rtype = rrdUops(w).lrs1_rtype
        val prs2 = rrdUops(w).prs2
        val lrs2Rtype = rrdUops(w).lrs2_rtype
        val ppred = rrdUops(w).ppred

        for(b <- 0 until numTotalBypassPorts){
            val bypass = io.bypass(b)

            rs1Cases ++= Array((bypass.valid && (prs1 === bypass.bits.uop.pdst) && bypass.bits.uop.rf_wen
             && bypass.bits.uop.dst_rtype === RT_FIX && lrs1Rtype === RT_FIX && (prs1 =/= 0.U),bypass.bits.data))
            rs2Cases ++= Array((bypass.valid && (prs2 === bypass.bits.uop.pdst) && bypass.bits.uop.rf_wen 
             && bypass.bits.uop.dst_rtype === RT_FIX && lrs2Rtype === RT_FIX && (prs2 =/= 0.U),bypass.bits.data))


        }

        for(b <- 0 until numTotalPredBypassPorts){
            val bypass = io.pred_bypass(b)
            predCases ++= Array((bypass.valid && (ppred === bypass.bits.uop.pdst) && bypass.bits.uop.is_sfb_br,bypas.bits.data))

        }

        if(numReadPorts > 0) bypassedRs1Data(w) := MuxCase(rrdRs1Data(w),rs1Cases)
        if(numReadPorts > 1) bypassedRs2Data(w) := MuxCase(rrdRs2Data(w),rs2Cases)
        if(enableSFBOpt) bypassedPredData(w) := MuxCase(rrdPredData(w),predCases) 
    }

    for(w <- 0 until issueWidth){
        val numReadPorts = numReadPortsArray(w)
        if(numReadPorts > 0) exeRegRs1Data(w) := bypassedRs1Data(w)
        if(numReadPorts > 1) exeRegRs2Data(w) := bypassedRs2Data(w)
        if(numRedaPorts > 2) exeRegRs3Data(w) := rrdRs3Data(w)
        if(enableSFBOpt) exseRegPredData(w) := bypassedPredData(w)
    }

    for(w <- 0 until issueWidth) {
        val numReadPorts = numReadPortsArray(w)

        io.exe_reqs(w).valid := exeRegValids(w)
        io.exe_reqs(w).bits.uop := exeRegUops(w)

        if(numReadPorts > 0) io.exe_reqs(w).bits.rs1_data := exeRegRs1Data(w)
        if(numReadPorts > 1) io.exe_reqs(w).bits.rs2_data := exeRegRs2Data(w)
        if(numReadPorts > 2) io.exe_reqs(w).bits.rs3_data := exeRegRs3Data(w)
        if(enableSFBOpt) io.exe_reqs(w).bits.pred_data := exeRegPredData(w)
    }
    
    


}