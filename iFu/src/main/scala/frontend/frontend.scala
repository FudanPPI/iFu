package iFu.frontend

import chisel3._
import chisel3.util._

import iFu.common._
import iFu.common.Consts._

import iFu.backend.{PreDecode, PreDecodeSignals}
// import iFu.frontend.Parameters._

//TODO 重命名FrontEndExceptions,GlobalHistory,如果之后RAS增加计数器，Histories的update函数需要更改，
//TODO FetchBundle重命名为FetchBufferEntry
//TODO 检查valid信号 569行
//TODO HasFrontEndparameters的函数只适用与ICache

class FrontendExceptions extends Bundle {
    val pf = Bool()
    val gf = Bool()
    val ae = Bool()
}

class GlobalHistory extends CoreBundle {
    /*--------------------------*/
    val globalHistoryLength = frontendParams.bpdParams.globalHistoryLength
    val nRasEntries         = frontendParams.bpdParams.nRasEntries
    val fetchWidth          = frontendParams.fetchWidth
    val bankWidth           = frontendParams.bankWidth
    /*--------------------------*/
    val old_history = UInt((globalHistoryLength).W)
    val current_saw_branch_not_taken = Bool()
    val new_saw_branch_not_taken = Bool()
    val new_saw_branch_taken = Bool()

    val ras_idx = UInt(log2Ceil(nRasEntries).W)

    def histories(bank: Int): UInt = {
        if (bank == 0) {
            old_history
        } else {
            Mux(new_saw_branch_taken,       old_history << 1 | 1.U,
            Mux(new_saw_branch_not_taken,   old_history << 1,
                                            old_history))
        }
    }

    def ===(other: GlobalHistory): Bool = {
        ((old_history === other.old_history) &&
         (new_saw_branch_not_taken === other.new_saw_branch_not_taken) &&
         (new_saw_branch_taken === other.new_saw_branch_taken))
    }

    def =/=(other: GlobalHistory): Bool = !(this === other)

    def update(
        branches: UInt, cfi_taken: Bool, cfi_is_br: Bool, cfi_idx: UInt,
        cfi_valid: Bool, addr: UInt, cfi_is_call: Bool, cfi_is_ret: Bool
    ): GlobalHistory = {
        val cfi_idx_fixed = cfi_idx(log2Ceil(fetchWidth) - 1, 0)
        val cfi_idx_oh = UIntToOH(cfi_idx_fixed)
        val new_history = Wire(new GlobalHistory)
        val not_taken_branches = branches & Mux(cfi_valid,
            MaskLower(cfi_idx_oh) & ~Mux(cfi_is_br && cfi_taken, cfi_idx_oh, 0.U(fetchWidth.W)),
            ~(0.U(fetchWidth.W))
        )
        val cfi_in_bank_0 = cfi_valid && cfi_taken && cfi_idx_fixed < bankWidth.U
        val ignore_second_bank = cfi_in_bank_0 || mayNotBeDualBanked(addr)

        val first_bank_saw_not_taken = not_taken_branches(bankWidth - 1, 0) =/= 0.U || current_saw_branch_not_taken
        new_history.current_saw_branch_not_taken := false.B
        when (ignore_second_bank) {
            new_history.old_history := histories(1)
            new_history.new_saw_branch_not_taken := first_bank_saw_not_taken
            new_history.new_saw_branch_taken := cfi_is_br && cfi_in_bank_0
        } .otherwise {
            new_history.old_history :=
                Mux(cfi_is_br && cfi_in_bank_0, histories(1) << 1 | 1.U,
                Mux(first_bank_saw_not_taken,   histories(1) << 1,
                                                histories(1)))
            new_history.new_saw_branch_not_taken := not_taken_branches(fetchWidth - 1, bankWidth)
            new_history.new_saw_branch_taken := cfi_valid && cfi_is_br && !cfi_in_bank_0
        }

        new_history.ras_idx :=
            Mux(cfi_valid && cfi_is_call,   WrapInc(ras_idx, nRasEntries),
            Mux(cfi_valid && cfi_is_ret,    WrapDec(ras_idx, nRasEntries),
                                            ras_idx))
        new_history
    }
}

class FetchResp extends CoreBundle {
    /*--------------------------*/
    val fetchWidth = frontendParams.fetchWidth
    /*--------------------------*/
    val pc      = UInt(vaddrBits.W)
    val data    = UInt((fetchWidth * coreInstrBits).W)
    val mask    = UInt((fetchWidth).W)
    val xcpt    = new FrontendExceptions
    val ghist   = new GlobalHistory
    val fsrc    = UInt(BSRC_SZ.W)
}

class FetchBundle extends CoreBundle {
    /*--------------------------*/
    val fetchWidth         = frontendParams.fetchWidth
    val fetchBytes         = frontendParams.fetchBytes
    val numFTQEntries      = frontendParams.numFTQEntries
    val nBanks             = frontendParams.iCacheParams.nBanks
    val localHistoryLength = frontendParams.bpdParams.localHistoryLength
    /*--------------------------*/
    val pc          = UInt(vaddrBits.W)
    val next_pc     = UInt(vaddrBits.W)
    val insts       = Vec(fetchWidth, Bits(coreInstrBits.W))
    val exp_insts   = Vec(fetchWidth, Bits(coreInstrBits.W))
    val sfbs        = Vec(fetchWidth,Bool())
    val sfb_masks   = Vec(fetchWidth,UInt((2*fetchWidth).W))
    val sfb_dests   = Vec(fetchWidth,UInt((1+log2Ceil(fetchBytes)).W))
    val shadowable_mask = Vec(fetchWidth,Bool())
    val shadowed_mask   = Vec(fetchWidth,Bool())

    val cfi_idx     = Valid(UInt(log2Ceil(fetchWidth).W))
    val cfi_type    = UInt(CFI_SZ.W)
    val cfi_is_call = Bool()
    val cfi_is_ret  = Bool()
    val cfi_npc_plus4   = Bool()

    val ras_top     = UInt(vaddrBits.W)

    val ftq_idx     = UInt(log2Ceil(numFTQEntries).W)
    val mask        = UInt(fetchWidth.W)

    val br_mask     = UInt(fetchWidth.W)

    val ghist       = new GlobalHistory
    val lhist       = Vec(nBanks,UInt(localHistoryLength.W))

    val xcpt_pf_if  = Bool()    //I-TLB miss(instruction fetch fault)
    val xcpt_ae_if  = Bool()    //Access exception

    val bp_debug_if_oh = Vec(fetchWidth,Bool())
    val bp_xcpt_if_oh   = Vec(fetchWidth,Bool())

    val bpd_meta    =Vec(nBanks,UInt())

    val fsrc        = UInt(BSRC_SZ.W)
    val tsrc        = UInt(BSRC_SZ.W)

}
//IO for the BOOM Frontend to/from the CPU
class FrontendToCPUIO extends CoreModule
{
    val numFTQEntries = frontendParams.numFTQEntries
    val fetchpacket     = Flipped(new DecoupledIO(new FetchBufferResp))

    // 1 for xcpt/jalr/auipc/flush
    val get_pc      = Flipped(Vec(2,new GetPCFromFtqIO()))

    //Breakpoint info
//    val status      = Output(new MStatus)
//    val bp          = Output(Vec(nBreakpoints,new BP))
//    val mcontext    = Output(UInt(mcontextWidth.W))
//    val scontext          = Output(UInt(scontextWidth.W))

    val sfence      = Valid(new SFenceReq)
    val brupdate    = Output(new BrUpdateInfo)

    //Redirects change the PC
    val redirect_flush  = Output(Bool())
    val redirect_val    = Output(Bool())
    val redirect_pc     = Output(UInt())    //分支指令的结果
    val redirect_ftq_idx= Output(UInt())
    val redirect_ghist  = Output(new GlobalHistory)

    val commit          = Valid(UInt(numFTQEntries.W))
    val flush_icache    = Output(Bool())
}
class FrontendIO extends CoreBundle {
    val cpu = Flipped(new FrontendToCPUIO())
//    val errors = new ICacheErrors
}

/**TODO
 * icache，ras，tlb,ptw的实例化
 * bpd的接口 .354 preds修改
 */
//TODO Frontend.273 bpd，RAS,icache的接口
class Frontend extends CoreModule
{
    val fetchWidth = frontendParams.fetchWidth


    val reset_addr = 0.U(vaddrBits)
    val io = IO(new FrontendIO)
    val io_reset_vector =reset_addr

    val bpd = Module(new BPD)
    bpd.io.f3fire := false.B
    val ras = Module(new RAS)

    val icache = Module(new ICache(frontendParams.iCacheParams))
    icache.io.invalidate := io.cpu.flush_icache
    val tlb = Module(new TLB)
//    io.cpu.perf.tlbMiss := io.ptw.req.fire
//    io.cpu.perf.acquire := icache.io.perf.acquire

    // --------------------------------------------------------
    // **** NextPC Select (F0) ****
    //      Send request to ICache
    // --------------------------------------------------------

    val s0_vpc      = WireInit(0.U(vaddrBits.W))
    val s0_ghist    = WireInit((0.U).asTypeOf(new GlobalHistory))
    val s0_tsrc     = WireInit(0.U(BSRC_SZ.W))
    val s0_valid    = WireInit(false.B)
    val s0_is_replay= WireInit(false.B)
    val s0_is_sfence= WireInit(false.B)
    val s0_replay_resp = Wire(new TLBResp)      //
    val s0_replay_bpd_resp = Wire(new BranchPredictionBundle)
    val s0_replay_ppc   = Wire(UInt())
    val s0_s1_use_f3_bpd_resp = WireInit(false.B)


    when(RegNext(reset.asBool) && !reset.asBool){
        s0_valid := true.B
        s0_vpc  := io_reset_vector
        s0_ghist    := (0.U).asTypeOf(new GlobalHistory)
        s0_tsrc     := BSRC_C
    }
    //icache的端口连接，只在这一处
    icache.io.req.valid     := s0_valid
    icache.io.req.bits.addr := s0_vpc

    bpd.io.f0_req.valid     := s0_valid
    bpd.io.f0_req.bits.pc   := s0_vpc
    bpd.io.f0_req.bits.ghist:= s0_ghist
    // --------------------------------------------------------
    // **** ICache Access (F1) ****
    //      Translate VPC
    // --------------------------------------------------------

    val s1_vpc      = RegNext(s0_vpc)
    val s1_valid    = RegNext(s0_valid,false.B)
    val s1_ghist    = RegNext(s0_ghist)
    val s1_is_replay= RegNext(s0_is_replay)
    val s1_is_sfence= RegNext(s0_is_sfence)
    val f1_clear    = WireInit(false.B)
    val s1_tsrc     = RegNext(s0_tsrc)
    tlb.io.req.valid            := (s1_valid && !s1_is_replay && !f1_clear) || s1_is_sfence
    tlb.io.req.bits.cmd         := DontCare
    tlb.io.req.bits.vaddr       := s1_vpc
    tlb.io.req.bits.passthrough := false.B  //may be changed
    tlb.io.req.bits.size        := log2Ceil(fetchWidth * coreInstrBytes)
//    tlb.io.req.bits.v           := io.ptw.status.v
//    tlb.io.req.bits.prv         := io.ptw.status.prv
    tlb.io.sfence               := RegNext(io.cpu.sfence)
//    tlb.io.kill                 := false.B
    //如果s1阶段将要进行replay，则不考虑tlb miss
    // 因为此时tlb resp的值是被replay的值，而被replay的值来源之前的s2
    //TODO 如果s2的指令发生tlb_miss咋办，信息已经保存在异常中了吗？
    val s1_tlb_miss = !s1_is_replay && tlb.io.resp.miss
    val s1_tlb_resp = Mux(s1_is_replay,RegNext(s0_replay_resp),tlb.io.resp)
    val s1_ppc = Mux(s1_is_replay,RegNext(s0_replay_ppc),tlb.io.resp.paddr)
    val s1_bpd_resp = bpd.io.resp.f1

    icache.io.s1_paddr  := s1_ppc
    icache.io.s1_kill   := tlb.io.resp.miss || f1_clear


    val f1_mask = fetchMask(s1_vpc) //有效的bank位置（"b01"或者"b11"）
    //根据bpd的f1的结果进行重定向
    val f1_redirects = (0 until fetchWidth) map { i=>
        s1_valid && f1_mask(i) && s1_bpd_resp.preds(i).predicted_pc.valid &&
                (s1_bpd_resp.preds(i).is_jal ||
                        s1_bpd_resp.preds(i).is_br && s1_bpd_resp.preds(i).taken)

    }
    val f1_redirect_idx = PriorityEncoder(f1_redirects)
    val f1_do_redirect = f1_redirects.reduce(_||_)
    val f1_targs = s1_bpd_resp.preds.map(_.predicted_pc.bits)
    val f1_predicted_target = Mux(f1_do_redirect,
        f1_targs(f1_redirect_idx),
        nextFetch(s1_vpc))
    val f1_predicted_ghist = s1_ghist.update(
        s1_bpd_resp.preds.map(p => p.is_br && p.predicted_pc.valid).asUInt & f1_mask,
        s1_bpd_resp.preds(f1_redirect_idx).taken && f1_do_redirect,
        s1_bpd_resp.preds(f1_redirect_idx).is_br,
        f1_redirect_idx,
        f1_do_redirect,
        s1_vpc,
        false.B,
        false.B)
    //当前s1寄存器有效。注意，s1阶段可能会replay
    when (s1_valid && !s1_tlb_miss){
        // 发生tlb异常时停止取指
        s0_valid    := !(s1_tlb_resp.ae.inst || s1_tlb_resp.pf.inst)
        s0_tsrc     := BSRC_1
        s0_vpc      := f1_predicted_target
        s0_ghist    := f1_predicted_ghist
        s0_is_replay := false.B
    }
    // --------------------------------------------------------
    // **** ICache Response (F2) ****
    // --------------------------------------------------------
    val s2_valid = RegNext(s1_valid && !f1_clear, false.B)
    val s2_vpc      = RegNext(s1_vpc)
    val s2_ghist    = Reg(new GlobalHistory)
    s2_ghist := s1_ghist
    val s2_ppc = RegNext(s1_ppc)
    val s2_tsrc = RegNext(s1_tsrc)
    val s2_fsrc = WireInit(BSRC_1)
    val f2_clear = WireInit(false.B)
    val s2_tlb_resp = RegNext(s1_tlb_resp)
    val s2_tlb_miss = RegNext(s1_tlb_miss)
    val s2_is_replay = RegNext(s1_is_replay) && s2_valid
    val s2_xcpt = s2_valid && (s2_tlb_resp.ae.inst || s2_tlb_resp.pf.inst) && !s2_is_replay
    val f3_ready = Wire(Bool())

    icache.io.s2_kill := s2_xcpt

    val f2_bpd_resp = bpd.io.resp.f2
    val f2_mask = fetchMask(s2_vpc)
    val f2_redirects = (0 until fetchWidth) map { i =>
        s2_valid && f2_mask(i) && f2_bpd_resp.preds(i).predicted_pc.valid &&
                (f2_bpd_resp.preds(i).is_jal ||
                        (f2_bpd_resp.preds(i).is_br && f2_bpd_resp.preds(i).taken))
    }
    val f2_redirect_idx = PriorityEncoder(f2_redirects)
    val f2_targs = f2_bpd_resp.preds.map(_.predicted_pc.bits)
    val f2_do_redirect = f2_redirects.reduce(_||_)
    val f2_predicted_target = Mux(f2_do_redirect,
        f2_targs(f2_redirect_idx),
        nextFetch(s2_vpc))
    val f2_predicted_ghist = s2_ghist.update(
        f2_bpd_resp.preds.map(p => p.is_br && p.predicted_pc.valid).asUInt & f2_mask,
        f2_bpd_resp.preds(f2_redirect_idx).taken && f2_do_redirect,
        f2_bpd_resp.preds(f2_redirect_idx).is_br,
        f2_redirect_idx,
        f2_do_redirect,
        s2_vpc,
        false.B,
        false.B)
    val f2_correct_f1_ghist = s1_ghist =/= f2_predicted_ghist
    //当本周期s2需要阻塞时，下一周期s2寄存器valid为假，并且会将内容传给s1寄存器（通过s0）
    when((s2_valid && !icache.io.resp.valid) ||
            (s2_valid && icache.io.resp.valid && !f3_ready)){
        s0_valid := (!s2_tlb_resp.ae.inst && !s2_tlb_resp.pf.inst) || s2_is_replay || s2_tlb_miss
        s0_vpc := s2_vpc
        s0_is_replay := s2_valid && icache.io.resp.valid
        // When this is not a replay (it queried the BPDs, we should use f3 resp in the replaying s1)
        //会传给bpd，当s2_is_replay时，用bpd f3的预测结果来传给s1
        s0_s1_use_f3_bpd_resp := !s2_is_replay
        s0_ghist := s2_ghist
        s0_tsrc := s2_tsrc
        f1_clear := true.B
    }.elsewhen(s2_valid && f3_ready) {  //预测有效时，不需要更改s0
        when(s1_valid && s1_vpc === f2_predicted_target && !f2_correct_f1_ghist){
            s2_ghist := f2_predicted_ghist
        }
        when((s1_valid && (s1_vpc =/= f2_predicted_target || f2_correct_f1_ghist)) || !s1_valid){
            f1_clear    := true.B

            s0_valid    := !((s2_tlb_resp.ae.inst || s2_tlb_resp.pf.inst) && !s2_is_replay)
            s0_vpc      := f2_predicted_target
            s0_is_replay:= false.B
            s0_ghist    := f2_predicted_ghist
            s2_fsrc     := BSRC_2
            s0_tsrc     := BSRC_2
        }
    }
    s0_replay_bpd_resp := f2_bpd_resp
    s0_replay_resp := s2_tlb_resp
    s0_replay_ppc := s2_ppc
    // --------------------------------------------------------
    // **** F3 ****
    // --------------------------------------------------------
    val f3_clear = WireInit(false.B)
    val f3 = withReset(reset.asBool || f3_clear){
        Module(new Queue(new FetchResp,1,pipe = true, flow=false))
    }
    //f3_bpd_resp的输入是f3的数据，输出也是f3的数据（flow）。
    val f3_bpd_resp = withReset(reset.asBool || f3_clear){
        Module(new Queue(new BranchPredictionBundle,1,pipe = true, flow = true))
    }

    val f4_ready = Wire(Bool())
    f3_ready := f3.io.enq.ready

    /**
     * 进s3条件：
     *  1.s2指令有效
     *  2.icache返回结果有效，或者是tlb除了miss以外的异常。miss情况可能可以由ptw解决，所以暂时不入队
     */
    //TODO 没有ptw,s2_tlb_miss或许不需要判定is_replay，并把s2_tlb_miss与ae,pf合在一起
    f3.io.enq.valid     := (s2_valid && !f2_clear &&
            (icache.io.resp.valid || ((s2_tlb_resp.ae.inst || s2_tlb_resp.pf.inst) && !s2_tlb_miss))
            )
    f3.io.enq.bits.pc := s2_vpc
    f3.io.enq.bits.data := Mux(s2_xcpt, 0.U, icache.io.resp.bits.data)
    f3.io.enq.bits.ghist := s2_ghist
    f3.io.enq.bits.mask := fetchMask(s2_vpc)
    f3.io.enq.bits.xcpt := s2_tlb_resp
    f3.io.enq.bits.fsrc := s2_fsrc
//    f3.io.enq.bits.tsrc := s2_tsrc
    val numRasEntries = frontendParams.bpdParams.numRasEntries
    //RAS输入在s2，输出在s3
    val ras_read_idx = RegInit(0.U(log2Ceil(numRasEntries).W))
    ras.io.read_idx := ras_read_idx
    when(f3.io.enq.fire){
        ras_read_idx := f3.io.enq.bits.ghist.ras_idx
        ras.io.read_idx := f3.io.enq.bits.ghist.ras_idx
    }
    //
    f3_bpd_resp.io.enq.valid := f3.io.deq.valid && RegNext(f3.io.enq.ready)
    f3_bpd_resp.io.enq.bits := bpd.io.resp.f3
    when (f3_bpd_resp.io.enq.fire){
        bpd.io.f3_fire := true.B
    }

    f3.io.deq.ready := f4_ready
    f3_bpd_resp.io.deq.ready := f4_ready

    val f3_imemresp = f3.io.deq.bits
    val f3_bank_mask    = bankMask(f3_imemresp.pc)
    val f3_data         = f3_imemresp.data
    val f3_aligned_pc   = bankAlign(f3_imemresp.pc)
    val f3_is_last_bank_in_block = isLastBankInBlock(f3_aligned_pc)
//    val f3_is_rvc   = Wire(Vec(fetchWidth,Bool())) 后面代码删去RVC相关的部分
    val f3_redirects       = Wire(Vec(fetchWidth,Bool()))
    val f3_targs            = Wire(fetchWidth,UInt(vaddrBits.W))
    val f3_cfi_types        = Wire(fetchWidth,UInt(CFI_SZ.W))
    val f3_shadowed_mask    = Wire(Vec(fetchWidth,Bool()))
    val f3_fetch_bundle     = Wire(new FetchBundle)
    val f3_mask             = Wire(Vec(fetchWidth,Bool()))
    val f3_br_mask          = Wire(Vec(fetchWidth,Bool()))
    val f3_call_mask        = Wire(Vec(fetchWidth,Bool()))
    val f3_ret_mask         = Wire(Vec(fetchWidth,Bool()))
    val f3_btb_mispredicts  = Wire(Vec(fetchWidth,Bool()))

    //连线，将信号整合到f3_fetch_bundle里面。
    f3_fetch_bundle.mask := f3_mask.asUInt
    f3_fetch_bundle.br_mask := f3_br_mask.asUInt
    f3_fetch_bundle.pc      := f3_imemresp.pc
    f3_fetch_bundle.ftq_idx := 0.U      //之后会被赋值
    f3_fetch_bundle.xcpt_pf_if := f3_imemresp.xcpt.pf.inst
    f3_fetch_bundle.xcpt_ae_if := f3_imemresp.xcpt.ae.inst
    f3_fetch_bundle.fsrc := f3_imemresp.fsrc
//    f3_fetch_bundle.tsrc := f3_imemresp.tsrc
    f3_fetch_bundle.shadowed_mask := f3_shadowed_mask

    var redirect_found = false.B
    for(b <- 0 until nBanks){
        val bank_data = f3_data((b+1)*bankWidth*coreInstrBits -1, b*bankWidth*coreInstrBits)
        val bank_mask = Wire(Vec(bankWidth,Bool))
        val bank_insts = Wire(Vec(bankWidth,UInt(coreInstrBits.W)))

        for(w <-0 until bankWidth){
            val i = (b * bankWidth) + w

            val valid = true.B
            val bpu = Module(new BreakpointUnit(nBreakpoints))
            bpu.io.status   := io.cpu.status
            bpu.io.bp := io.cpu.bp
            bpu.io.ea := DontCare
            bpu.io.mcontext := io.cpu.mcontext
            bpu.io.scontext := io.cpu.scontext

            val brsigs = Wire(new PreDecodeSignals)
            val inst = Wire(UInt(coreInstrBits.W))
            val pc = f3_aligned_pc + (i << log2Ceil(coreInstrBits))
            val bpd_decoder = Module(new PreDecode)
            bpd_decoder.io.inst := inst
            bpd_decoder.io.pc   := pc

            bank_insts(w)               := inst
            f3_fetch_bundle.insts(i)    := inst
            bpu.io.pc                   := pc
            brsigs                      := bpd_decoder.io.out
            inst  := bank_data(w*coreInstrBits+coreInstrBits-1,w*coreInstrBits)
            bank_mask(w)    := f3.io.deq.valid && f3_imemresp.mask(i) && valid && !redirect_found
            f3_mask(i)      := f3.io.deq.valid && f3_imemresp.mask(i) && valid && !redirect_found
            f3_targs(i)     := Mux(brsigs.cfiType === CFI_JALR,     //JALR预测的结果会错，而Br指令只有方向会错
                f3_bpd_resp.io.deq.bits.preds(i).predicted_pc.bits,
                brsigs.target)
            //TODO JAL结果的判断能提前吗？由于ICache，不能
            f3_btb_mispredicts(i) := (brsigs.cfiType === CFI_JAL && valid &&
                    f3_bpd_resp.io.deq.bits.preds(i).predicted_pc.valid &&
                    (f3_bpd_resp.io.deq.bits.preds(i).predicted_pc =/= brsigs.target)
                    )
                //i << 1是防止溢出，因为sfbOffset <= Cacheline
            val offset_from_aligned_pc = (
                    (i<<1).U((log2Ceil(icBlockBytes)+1).W) + brsigs.sfbOffset.bits
            )
            val lower_mask = Wire(UInt((2*fetchWidth).W))
            val upper_mask = Wire(UInt((2*fetchWidth).W))
            lower_mask := UIntToOH(i.U)
            upper_mask := UIntToOH(offset_from_aligned_pc(log2Ceil(fetchBytes)+1,1)) << Mux(f3_is_last_bank_in_block, bankWidth.U, 0.U)

            /**
             * 判断是否是sfb指令，如果是Cacheline的最后一个bank，那么最多只能取3个bank（跨Cacheline）
             * 正常情况，可以取4个bank
             */
            f3_fetch_bundle.sfbs(i) := (
                    f3_mask(i) &&
                            brsigs.sfbOffset.valid &&
                            (offset_from_aligned_pc <= Mux(f3_is_last_bank_in_block,(fetchBytes + bankBytes).U,(2*fetchBytes).U))
            )
            f3_fetch_bundle.sfb_masks(i)    := ~MaskLower(lower_mask) & ~MaskUpper(upper_mask)

            /**
             * 没有发生异常
             * bank有效
             * 是shadowable的或者该位置指令无效
             */
            f3_fetch_bundle.shadowable_mask(i) := (!(f3_fetch_bundle.xcpt_pf_if || f3_fetch_bundle.xcpt_ae_if || bpu.io.debug_if || bpu.io.xcpt_if) &&
                    f3_bank_mask(b) &&
                    (brsigs.shadowable || !f3_mask(i)))
            f3_fetch_bundle.sfb_dests(i) := offset_from_aligned_pc

            /**
                s3阶段重定向的条件
                1.是jal/jalr指令
                2.是条件分支指令并被预测跳转(sfb不被预测)
             */
            f3_redirects(i)     := f3_mask(i) && (
                    brsigs.cfiType === CFI_JAL || brsigs.cfiType === CFI_JALR ||
                            (brsigs.cfiType === CFI_BR && f3_bpd_resp.io.deq.preds(i).taken)
            )

            f3_br_mask(i)   := f3_mask(i) && brsigs.cfiType === CFI_BR
            f3_cfi_types(i) := brsigs.cfiType
            f3_call_mask(i) := brsigs.isCall
            f3_ret_mask(i)  := brsigs.isRet

            f3_fetch_bundle.bp_debug_if_oh(i)   := bpu.io.debug_if
            f3_fetch_bundle.bp_xcpt_if_oh(i)    := bpu.io.xcpt_if

            redirect_found = redirect_found || f3_redirects(i)
        }

    }
    f3_fetch_bundle.cfi_type      := f3_cfi_types(f3_fetch_bundle.cfi_idx.bits)
    f3_fetch_bundle.cfi_is_call := f3_call_mask(f3_fetch_bundle.cfi_idx.bits)
    f3_fetch_bundle.cfi_is_ret := f3_ret_mask(f3_fetch_bundle.cfi_idx.bits)

    f3_fetch_bundle.ghist := f3.io.deq.bits.ghist
    f3_fetch_bundle.lhist := f3_bpd_resp.io.deq.bits.lhist
    f3_fetch_bundle.bpd_meta := f3_bpd_resp.io.deq.bits.meta

    f3_fetch_bundle.cfi_idx.valid := f3_redirects.reduce(_ || _)
    f3_fetch_bundle.cfi_idx.bits := PriorityEncoder(f3_redirects)

    f3_fetch_bundle.ras_top := ras.io.read_addr

    val f3_predicted_target = Mux(f3_redirects.reduce(_||_),
        Mux(f3_fetch_bundle.cfi_is_ret,
            ras.io.read_addr,
            f3_targs(PriorityEncoder(f3_redirects))
        ),
        nextFetch(f3_fetch_bundle.pc)
    )

    f3_fetch_bundle.next_pc     := f3_predicted_target
    val f3_predicted_ghist = f3_fetch_bundle.ghist.update(
        f3_fetch_bundle.br_mask,
        f3_fetch_bundle.cfi_idx.valid,
        f3_fetch_bundle.br_mask(f3_fetch_bundle.cfi_idx.bits),
        f3_fetch_bundle.cfi_idx.bits,
        f3_fetch_bundle.cfi_idx.valid,
        f3_fetch_bundle.pc,
        f3_fetch_bundle.cfi_is_call,
        f3_fetch_bundle.cfi_is_ret
    )

    ras.io.write_valid := false.B
    ras.io.write_addr   := f3_aligned_pc + ((f3_fetch_bundle.cfi_idx.bits << 1)) + 4
    ras.io.write_idx    := WrapInc(f3_fetch_bundle.ghist.ras_idx,nRasEntries)

    val f3_correct_f1_ghist = s1_ghist =/= f3_predicted_ghist
    val f3_correct_f2_ghist = s2_ghist =/= f3_predicted_ghist

    when(f3.io.deq.valid && f4_ready){
        when(f3_fetch_bundle.cfi_is_call && f3_fetch_bundle.cfi_idx.valid){
            ras.io.write_valid := true.B
        }
        when (s2_valid && s2_vpc === f3_predicted_target&& !f3_correct_f2_ghist){
            f3.io.enq.bits.ghist := f3_predicted_ghist
        } .elsewhen(!s2_valid && s1_valid && s1_vpc === f3_predicted_target && !f3_correct_f1_ghist){
            s2_ghist := f3_predicted_ghist
        } .elsewhen((s2_valid && (s2_vpc =/= f3_predicted_target|| f3_correct_f2_ghist)) ||
                (!s2_valid && s1_valid && (s1_vpc =/= f3_predicted_target || f3_correct_f1_ghist))||
                (!s2_valid && !s1_valid)){
            f2_clear := true.B
            f1_clear := true.B
            s0_valid    := !(f3_fetch_bundle.xcpt_pf_if || f3_fetch_bundle.xcpt_ae_if)
            s0_vpc      := f3_predicted_target
            s0_is_replay:= false.B
            s0_ghist    := f3_predicted_ghist
            s0_tsrc     := BSRC_3

            f3_fetch_bundle.fsrc := BSRC_3
        }
    }
    //当f3发现btb预测错误时，建一个队列来存储bpd更新
    val f4_btb_corrections = Module(new Queue(new BranchPredictionUpdate,2))
    f4_btb_corrections.io.enq.valid := f3.io.deq.fire && f3_btb_mispredicts.reduce(_||_) && enableBTBFastRepair.B
    f4_btb_corrections.io.enq.bits  := DontCare
    f4_btb_corrections.io.enq.bits.is_mispredict_update := false.B
    f4_btb_corrections.io.enq.bits.is_repair_update     := false.B
    f4_btb_corrections.io.enq.bits.btb_mispredicts      := f3_btb_mispredicts.asUInt
    f4_btb_corrections.io.enq.bits.pc                   := f3_fetch_bundle.pc
    f4_btb_corrections.io.enq.bits.ghist                := f3_fetch_bundle.ghist
    f4_btb_corrections.io.enq.bits.lhist                := f3_fetch_bundle.lhist
    f4_btb_corrections.io.enq.bits.meta                 := f3_fetch_bundle.bpd_meta

    // -------------------------------------------------------
    // **** F4 ****
    // -------------------------------------------------------
    val f4_clear    = WireInit(false.B)
    val f4          = withReset(reset.asBool || f4_clear){
        Module(new Queue(new FetchBundle,1,pipe=true,flow=false))
    }
    val fb = Module(new FetchBuffer)
    val ftq = Module(new FetchTargetQueue)

    //下面将要处理sfbs
    val f4_shadowable_masks = VecInit((0 until fetchWidth) map { i =>
        f4.io.deq.bits.shadowable_mask.asUInt |
                ~f4.io.deq.bits.sfb_masks(i)(fetchWidth-1,0)
    })
    val f3_shadowable_masks = VecInit((0 until fetchWidth) map { i =>
        Mux(f4.io.enq.valid, f4.io.enq.bits.shadowable_mask.asUInt, 0.U) |
                ~f4.io.deq.bits.sfb_masks(i)(2 * fetchWidth - 1, fetchWidth)
    })
    val f4_sfbs = VecInit((0 until fetchWidth) map {i =>
        ((~f4_shadowable_masks(i) === 0.U) &&
                (~f3_shadowable_masks(i) === 0.U) &&
                f4.io.deq.bits.sfbs(i) &&
                !(f4.io.deq.bits.cfi_idx.valid && f4.io.deq.bits.cfi_idx.bits === i.U)   //TODO 边界检查？
                )
    })
    val f4_sfb_valid    = f4_sfbs.reduce(_||_) && f4.io.deq.valid
    val f4_sfb_idx      = PriorityEncoder(f4_sfbs)
    val f4_sfb_mask     = f4.io.deq.bits.sfb_masks(f4_sfb_idx)

    //如果f4阶段有sfb指令，要等待下一次fetch
    val f4_delay        = (
            f4.io.deq.bits.sfbs.reduce(_||_) &&
                    !f4.io.deq.bits.cfi_idx.valid &&
                    !f4.io.enq.valid &&
                    !(f4.io.deq.bits.xcpt_pf_if ||f4.io.deq.bits.xcpt_ae_if)
    )
    when (f4_sfb_valid){
        f3_shadowed_mask := f4_sfb_mask(2*fetchWidth-1,fetchWidth).asBools
    }.otherwise{
        f3_shadowed_mask := VecInit(0.U(fetchWidth.W).asBools)
    }

    f4_ready := f4.io.enq.ready
    f4.io.enq.valid := f3.io.deq.valid && !f3_clear
    f4.io.enq.bits  := f3_fetch_bundle
    f4.io.deq.ready := fb.io.enq.ready && ftq.io.enq.ready && !f4_delay

    fb.io.enq.valid := f4.io.deq.valid && ftq.io.enq.ready && !f4_delay
    fb.io.enq.bits  := f4.io.deq.bits
    fb.io.enq.bits.ftqIdx  := ftq.io.enqIdx
    fb.io.enq.bits.sfbs     := Mux(f4_sfb_valid,UIntToOH(f4_sfb_idx),0.U(fetchWidth.W)).asBools
    fb.io.enq.bits.shadowedMask := (
            Mux(f4_sfb_valid,f4_sfb_mask(fetchWidth-1,0),0.U(fetchWidth.W)) |
                    f4.io.deq.bits.shadowed_mask.asUInt
    ).asBools

    ftq.io.enq.valid        := f4.io.deq.valid && fb.io.enq.ready && !f4_delay
    ftq.io.enq.bits         := f4.io.deq.bits

    val bpd_update_arbiter = Module(new Arbiter(new BranchPredictionUpdate,2))
    bpd_update_arbiter.io.in(0).valid := ftq.io.bpdupdate.valid
    bpd_update_arbiter.io.in(0).bits  := ftq.io.bpdupdate.bits
    assert(bpd_update_arbiter.io.in(0).ready)
    bpd_update_arbiter.io.in(1) <> f4_btb_corrections.io.deq
    bpd.io.update := bpd_update_arbiter.io.out
    bpd_update_arbiter.io.out.ready := true.B
    //ftq更新ras
    when(ftq.io.ras_update && enableRasTopRepair.B){
        ras.io.write_valid  := true.B
        ras.io.write_idx    := ftq.io.ras_update_idx
        ras.io.write_addr   := ftq.io.ras_update_pc
    }
    // -------------------------------------------------------
    // **** To Core (F5) ****
    // -------------------------------------------------------

    io.cpu.fetchpacket <> fb.io.deq
    io.cpu.get_pc <> ftq.io.get_ftq_pc
    ftq.io.deq  := io.cpu.commit
    ftq.io.brupdate := io.cpu.brupdate

    ftq.io.redirect.valid   := io.cpu.redirect_val
    ftq.io.redirect.bits    := io.cpu.redirect_ftq_idx
    fb.io.clear := false.B

    when(io.cpu.sfence.valid){
        fb.io.clear := true.B
        f4_clear    := true.B
        f3_clear    := true.B
        f2_clear    := true.B
        f1_clear    := true.B

        s0_valid    := false.B
        s0_vpc      := io.cpu.sfence.bits.addr
        s0_is_replay:= false.B
        s0_is_sfence    := true.B
    }.elsewhen(io.cpu.redirect_flush){
        fb.io.clear := true.B
        f4_clear    := true.B
        f3_clear    := true.B
        f2_clear := true.B
        f1_clear := true.B

        s0_valid := io.cpu.redirect_val
        s0_vpc := io.cpu.redirect_pc
        s0_ghist := io.cpu.redirect_ghist
        s0_tsrc := BSRC_C
        s0_is_replay := false.B

        ftq.io.redirect.valid := io.cpu.redirect_val
        ftq.io.redirect.bits := io.cpu.redirect_ftq_idx
    }
    ftq.io.debug_ftq_idx := io.cpu.debug_ftq_idx
    io.cpu.debug_fetch_pc := ftq.io.debug_fetch_pc

}
/*-----------------------------------------utils--------------------------------------*/
trait HasFrontendParameters
{
    // How many banks does the ICache use?
    // How many bytes wide is a bank?
    val bankBytes = fetchBytes/nBanks

    val bankWidth = fetchWidth/nBanks

    require(nBanks == 1 || nBanks == 2)



    // How many "chunks"/interleavings make up a cache line?
    val numChunks = icBlockBytes / bankBytes

    // Which bank is the address pointing to?
    def bank(addr: UInt) = if (nBanks == 2) addr(log2Ceil(bankBytes)) else 0.U
    def isLastBankInBlock(addr: UInt) = {
        (nBanks == 2).B && addr(icBlockOffBits-1, log2Ceil(bankBytes)) === (numChunks-1).U
    }
    def mayNotBeDualBanked(addr: UInt) = {
        require(nBanks == 2)
        isLastBankInBlock(addr)
    }

    def blockAlign(addr: UInt) = ~(~addr | (icBlockBytes-1).U)
    def bankAlign(addr: UInt) = ~(~addr | (bankBytes-1).U)

    def fetchIdx(addr: UInt) = addr >> log2Ceil(fetchBytes)

    def nextBank(addr: UInt)= bankAlign(addr) + bankBytes.U
    def nextFetch(addr: UInt) = {

        require(nBanks == 2)
        bankAlign(addr) + Mux(mayNotBeDualBanked(addr), bankBytes.U, fetchBytes.U)
    }

    def fetchMask(addr: UInt) = {
        val idx = addr.extract(log2Ceil(fetchWidth)+log2Ceil(coreInstrBytes)-1, log2Ceil(coreInstrBytes))
        if (nBanks == 1) {
            ((1 << fetchWidth)-1).U << idx
        } else {
            val shamt = idx.extract(log2Ceil(fetchWidth)-2, 0)
            val end_mask = Mux(mayNotBeDualBanked(addr), Fill(fetchWidth/2, 1.U), Fill(fetchWidth, 1.U))
            ((1 << fetchWidth)-1).U << shamt & end_mask
        }
    }

    def bankMask(addr: UInt) = {
        val idx = addr.extract(log2Ceil(fetchWidth)+log2Ceil(coreInstrBytes)-1, log2Ceil(coreInstrBytes))
        if (nBanks == 1) {
            1.U(1.W)
        } else {
            Mux(mayNotBeDualBanked(addr), 1.U(2.W), 3.U(2.W))
        }
    }
}

object WrapDec
{
    // "n" is the number of increments, so we wrap at n-1.
    def apply(value: UInt, n: Int): UInt = {
        if (isPow2(n)) {
            (value - 1.U)(log2Ceil(n)-1,0)
        } else {
            val wrap = (value === 0.U)
            Mux(wrap, (n-1).U, value - 1.U)
        }
    }
}
object WrapInc
{
    // "n" is the number of increments, so we wrap at n-1.
    def apply(value: UInt, n: Int): UInt = {
        if (isPow2(n)) {
            (value + 1.U)(log2Ceil(n)-1,0)
        } else {
            val wrap = (value === (n-1).U)
            Mux(wrap, 0.U, value + 1.U)
        }
    }
}