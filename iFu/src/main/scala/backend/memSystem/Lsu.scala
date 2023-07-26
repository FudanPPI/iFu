package backend.memSystem

import chisel3._
import chisel3.util._

import iFu.common._
import backend.memSystem.LSUConsts._    // TODO
import iFu.backend.CommitSignals        // TODO
import iFu.frontend.WrapInc             // TODO

//TODO  memWidth为2，如果为1，需要wakeup不成功后增加一周期delay  Done
//TODO tlb_miss抛出异常。
//TODO 删除Hella Cache相关的代码  Done
//TODO 删除release，observed等代码，load可以乱序   Done
//TODO 增加TLB相关逻辑，包括异常检测，发送DCache请求时的地址,发送数据给tlb
//TODO LSU会将vaddr的idx位发给DCache，TLB_MISS在s1_kill掉请求
object IsKilledByBranch {
    def apply(brupdate: BrUpdateInfo, uop: MicroOp): Bool = {
        return maskMatch(brupdate.b1.mispredict_mask, uop.br_mask)
    }

    def apply(brupdate: BrUpdateInfo, uop_mask: UInt): Bool = {
        return maskMatch(brupdate.b1.mispredict_mask, uop_mask)
    }
}

object AgePriorityEncoder {
    def apply(in: Seq[Bool], head: UInt): UInt = {
        val n = in.size
        val width = log2Ceil(in.size)
        val n_padded = 1 << width
        val temp_vec = (0 until n_padded).map(i => if (i < n) in(i) && i.U >= head else false.B) ++ in
        val idx = PriorityEncoder(temp_vec)
        idx(width-1, 0) //discard msb
    }
}

object IsOlder {
    def apply(i0: UInt, i1: UInt, head: UInt) = ((i0 < i1) ^ (i0 < head) ^ (i1 < head))
}

object LSUConsts {
    val xLen = 32
    val paddrBits = 32  // ???

    val memWidth = 2
    val robAddrSz = 5   // TODO

    val numLdqEntries = 32
    val numStqEntries = 32
    val numIntPhysRegs= 108

    val ldqAddrSz = log2Ceil(numLdqEntries)
    val stqAddrSz = log2Ceil(numStqEntries)
    val lsuAddrSz = max(ldqAddrSz, stqAddrSz)

    val maxPregSz = numIntPhysRegs
}

class LSUExeIO extends CoreBundle {
    val req     = Flipped(new Valid(new FuncUnitResp))
    val iresp   = new Decoupled(new ExeUnitResp)
}

class DCacheReq extends CoreBundle {
    val uop         = new MicroOp()
    val addr        = UInt(vaddrBits.W)
    val data        = Bits(xLen.W)
}

class DCacheResp extends CoreBundle {
    val uop  = new MicroOp()
    val data = Bits(xLen.W)
}

// lsu <> dcache 
class LSUDMemIO extends CoreBundle {
    val req         = new Decoupled(Vec(memWidth,Valid(new DCacheReq)))
    val s1_kill     = Output(Vec(memWidth, Bool()))
    val resp        = Flipped(Vec(memWidth, new Valid(new DCacheResp)))

    val nack        = Flipped(Vec(memWidth, new Valid(new DCacheReq)))

    val brupdate    = Output(new BrUpdateInfo)
    val exception   = Output(Bool())
    val rob_head_idx = Output(UInt(robAddrSz.W))

    val force_order = Output(Bool())
    val ordered     = Input(Bool())
}

/**
 * 输入dispatch阶段的uop，commit的信息，rob，brupdate
 * 输出ldq，stq的索引，是否full，给rob的clear信号
 */
// lsu <> core
class LSUCoreIO extends CoreBundle {
    val exe = Vec(memWidth, new LSUExeIO)

    val dis_uops    = Flipped(Vec(coreWidth, Valid(new MicroOp)))
    val dis_ldq_idx = Output(Vec(coreWidth, UInt(ldqAddrSz.W)))
    val dis_stq_idx = Output(Vec(coreWidth, UInt(stqAddrSz.W)))

    val ldq_full    = Output(Vec(coreWidth,Bool()))
    val stq_full    = Output(Vec(coreWidth,Bool()))

    val commit      = Input(new CommitSignals)
    val commit_load_at_rob_head = Input(Bool())

    val clr_bsy     = Output(Vec(memWidth,Valid(UInt(robAddrSz.W))))    // TODO

    val clr_unsafe  = Output(Vec(memWidth, Valid(UInt(robAddrSz.W))))

    val fence_dmem  = Input(Bool())

    // Speculatively tell the IQs that we'll get load data back next cycle
    val spec_ld_wakeup = Output(Vec(memWidth, Valid(UInt(maxPregSz.W))))
    val ld_miss     = Output(Bool())

    val brupdate    = Input(new BrUpdateInfo)
    val rob_head_idx= Input(UInt(robAddrSz.W))
    val exception   = Input(Bool())

    val fencei_rdy  = Output(Bool())

    val lxcpt       = Output(Valid(new Exception))
}

class LSUIO extends CoreBundle {
    val core    = new LSUCoreIO
    val dmem    = new LSUDMemIO
}

class LDQEntry extends CoreBundle {
    val uop         = new MicroOp
    val addr        = Valid(UInt(xLen.W))
    val addr_is_virtual = Bool()

    val executed    = Bool()
    val succeeded   = Bool() // dcache 返回结果
    val order_fail  = Bool() // raw 冒险

    val st_dep_mask = UInt(numStqEntries.W)
    val youngest_stq_idx = UInt(stqAddrSz.W) // 第一条晚于该load的store指令

    val forward_std_val = Bool()
    val forward_stq_idx = UInt(stqAddrSz.W)
}

class STQEntry extends CoreBundle {
    val uop     = new MicroOp
    val addr    = Valid(UInt(xLen.W))
    val addr_is_virtual = Bool()
    val data    = Valid(UInt(xLen.W))

    val committed   = Bool()
    val succeeded   = Bool() // 访存成功
}

class Lsu extends CoreModule {
/*=============================================================================*/
    def widthMap[T <: Data](f: Int => T) = VecInit((0 until memWidth).map(f))
/*=============================================================================*/
    val io = IO(new LSUIO)

    val ldq = Reg(Vec(numLdqEntries, Valid(new LDQEntry)))
    val stq = Reg(Vec(numStqEntries, Valid(new STQEntry)))

    val ldq_head = Reg(UInt(ldqAddrSz.W))
    val ldq_tail = Reg(UInt(ldqAddrSz.W))
    val stq_head = Reg(UInt(stqAddrSz.W)) // will dequeue
    val stq_tail = Reg(UInt(stqAddrSz.W))
    val stq_commit_head = Reg(UInt(stqAddrSz.W))  // point to next store to commit
    val stq_execute_head = Reg(UInt(stqAddrSz.W)) // point to next store to execute

    /* --------------------------------------------------------------------- */
    /* If we got a mispredict, the tail will be misaligned for 1 extra cycle */
    /* --------------------------------------------------------------------- */

    // TODO: check this
    assert(io.core.brupdate.b2.mispredict || stq(stq_execute_head).valid ||
           stq_head === stq_execute_head || stq_tail === stq_execute_head,
        "stq_execute_head got off track."
    )

    // TODO: add a dtlb / tlb ?
    //    val dtlb = Module(new NBDTLB(
    //        instruction = false, lgMaxSize = log2Ceil(coreDataBytes), rocket.TLBConfig(dcacheParams.nTLBSets, dcacheParams.nTLBWays)))

    val clear_store = WireInit(false.B) // 清空一条指令
    val live_store_mask = RegInit(0.U(numStqEntries.W)) // 当前哪些位置有store指令 // diff with valid?
    var next_live_store_mask = Mux(clear_store, 
        live_store_mask & ~(1.U << stq_head), // -> 如果store指令提交了，那么该位置零
        live_store_mask
    )

/*=============================================================================*/
    //-------------------------------------------------------------
    // Dequeue store entries
    //-------------------------------------------------------------
    for (i <- 0 until numLdqEntries) { // store指令提交后，修改ldq中与该条指令有关的位
        when(clear_store) {
            ldq(i).bits.st_dep_mask := ldq(i).bits.st_dep_mask & ~(1.U << stq_head)
        }
    }
    //-------------------------------------------------------------
    // Dequeue store entries
    //-------------------------------------------------------------
/*=============================================================================*/
    //-------------------------------------------------------------
    // Enqueue new entries
    //-------------------------------------------------------------
    var ld_enq_idx = ldq_tail
    var st_enq_idx = stq_tail
    var ldq_full = Bool() // 过程中出现ldq已满
    var stq_full = Bool() // 过程中出现stq已满

    for (w <- 0 until coreWidth) {
        ldq_full = WrapInc(ld_enq_idx, numLdqEntries) === ldq_head
        io.core.ldq_full(w) := ldq_full
        io.core.dis_ldq_idx(w) := ld_enq_idx

        stq_full = WrapInc(st_enq_idx, numStqEntries) === stq_head
        io.core.stq_full(w) := stq_full
        io.core.dis_stq_idx(w) := st_enq_idx

        // enq
        val dis_ld_val = io.core.dis_uops(w).valid && io.core.dis_uops(w).bits.use_ldq && !io.core.dis_uops(w).bits.exception
        val dis_st_val = io.core.dis_uops(w).valid && io.core.dis_uops(w).bits.use_stq && !io.core.dis_uops(w).bits.exception
        when(dis_ld_val) {
            ldq(ld_enq_idx).valid := true.B
            ldq(ld_enq_idx).bits.uop := io.core.dis_uops(w).bits
            ldq(ld_enq_idx).bits.youngest_stq_idx := st_enq_idx
            ldq(ld_enq_idx).bits.st_dep_mask := next_live_store_mask

            ldq(ld_enq_idx).bits.addr.valid := false.B
            ldq(ld_enq_idx).bits.executed := false.B
            ldq(ld_enq_idx).bits.succeeded := false.B
            ldq(ld_enq_idx).bits.order_fail := false.B
            ldq(ld_enq_idx).bits.forward_std_val := false.B

            assert(ld_enq_idx === io.core.dis_uops(w).bits.ldqIdx, "[lsu] mismatch enq load tag.")
            assert(!ldq(ld_enq_idx).valid, "[lsu] Enqueuing uop is overwriting ldq entries")
        } .elsewhen(dis_st_val) {
            stq(st_enq_idx).valid := true.B
            stq(st_enq_idx).bits.uop := io.core.dis_uops(w).bits

            stq(st_enq_idx).bits.addr.valid := false.B
            stq(st_enq_idx).bits.data.valid := false.B
            stq(st_enq_idx).bits.committed := false.B
            stq(st_enq_idx).bits.succeeded := false.B

            assert(st_enq_idx === io.core.dis_uops(w).bits.stqIdx, "[lsu] mismatch enq store tag.")
            assert(!stq(st_enq_idx).valid, "[lsu] Enqueuing uop is overwriting stq entries")
        }

        ld_enq_idx = Mux(dis_ld_val, WrapInc(ld_enq_idx, numLdqEntries), ld_enq_idx)
        st_enq_idx = Mux(dis_st_val, WrapInc(st_enq_idx, numStqEntries), st_enq_idx)

        next_live_store_mask = Mux(dis_st_val,      // 新增store指令
            next_live_store_mask | (1.U << st_enq_idx),
            next_live_store_mask
        )

        assert(!(dis_ld_val && dis_st_val), "A UOP is trying to go into both the LDQ and the STQ")
    }

    ldq_tail := ld_enq_idx
    stq_tail := st_enq_idx

    // TODO: cacop? fence?
    val stqEmpty = (0 until numStqEntries).map{ i => stq(i).valid }.asUInt === 0.U
    io.dmem.force_order := io.core.fence_dmem
    io.core.fencei_rdy := stqEmpty && io.dmem.ordered
    //-------------------------------------------------------------
    // Enqueue new entries
    //-------------------------------------------------------------
/*=============================================================================*/
    //-------------------------------------------------------------
    // Execute stage (access TLB, send requests to Memory)
    //-------------------------------------------------------------

    // TODO: which instruction should we select?
    val mem_xcpt_valid = Wire(Bool())
    val mem_xcpt_cause = Wire(UInt())
    val mem_xcpt_uop = Wire(new MicroOp)
    val mem_xcpt_vaddr = Wire(UInt())

    //---------------------------------------
    // Can-fire logic and wakeup/retry select
    //
    // First we determine what operations are waiting to execute.
    // These are the "can_fire"/"will_fire" signals

    val will_fire_load_incoming = Wire(Vec(memWidth, Bool()))
    val will_fire_stad_incoming = Wire(Vec(memWidth, Bool()))
    val will_fire_sta_incoming = Wire(Vec(memWidth, Bool()))
    val will_fire_std_incoming = Wire(Vec(memWidth, Bool()))
    val will_fire_sfence = Wire(Vec(memWidth, Bool()))
    val will_fire_store_commit = Wire(Vec(memWidth, Bool()))
    val will_fire_load_wakeup = Wire(Vec(memWidth, Bool()))

    val exe_req = WireInit(VecInit(io.core.exe.map(_.req)))

    // Sfence goes through all pipes
    for (i <- 0 until memWidth) {
        when(io.core.exe(i).req.bits.sfence.valid) {
            exe_req := VecInit(Seq.fill(memWidth){ io.core.exe(i).req })
        }
    }

    // -------------------------------
    // Assorted signals for scheduling

    // Don't wakeup a load if we just sent it last cycle or two cycles ago
    // The block_load_mask may be wrong, but the executing_load mask must be accurate
    val block_load_mask = WireInit(VecInit((0 until numLdqEntries).map(x => false.B)))
    val p1_block_load_mask = RegNext(block_load_mask)
    val p2_block_load_mask = RegNext(p1_block_load_mask)

    // The store at the commit head needs the DCache to appear ordered
    // Delay firing load wakeups and retries now
    val store_needs_order = WireInit(false.B)
    // 将要到来的ldq和stq里的元素
    val ldq_incoming_idx = widthMap(i => exe_req(i).bits.uop.ldq_idx)
    val ldq_incoming_e = widthMap(i => ldq(ldq_incoming_idx(i)))

    val stq_incoming_idx = widthMap(i => exe_req(i).bits.uop.stq_idx)
    val stq_incoming_e = widthMap(i => stq(stq_incoming_idx(i)))

    val stq_commit_e = stq(stq_execute_head)
    //TODO 使用RegNext，或许是为了改善时序，把wakeup_idx的选择放在s2
    val ldq_wakeup_idx = RegNext(AgePriorityEncoder((0 until numLdqEntries).map(i => {
        val e = ldq(i).bits
        val block = block_load_mask(i) || p1_block_load_mask(i)
        e.addr.valid && !e.executed && !e.succeeded && !e.addr_is_virtual && !block
    }), ldq_head))
    val ldq_wakeup_e = ldq(ldq_wakeup_idx)

    // -----------------------
    // Determine what can fire

    // Can we fire a incoming load
    val can_fire_load_incoming = widthMap(w => exe_req(w).valid && exe_req(w).bits.uop.ctrl.is_load)

    // Can we fire an incoming store addrgen + store datagen
    val can_fire_stad_incoming = widthMap(w => exe_req(w).valid && exe_req(w).bits.uop.ctrl.is_sta
            && exe_req(w).bits.uop.ctrl.is_std)

    // Can we fire an incoming store addrgen
    val can_fire_sta_incoming = widthMap(w => exe_req(w).valid && exe_req(w).bits.uop.ctrl.is_sta
            && !exe_req(w).bits.uop.ctrl.is_std)

    // Can we fire an incoming store datagen
    val can_fire_std_incoming = widthMap(w => exe_req(w).valid && exe_req(w).bits.uop.ctrl.is_std
            && !exe_req(w).bits.uop.ctrl.is_sta)

    // Can we fire an incoming sfence
    val can_fire_sfence = widthMap(w => exe_req(w).valid && exe_req(w).bits.sfence.valid)

    // Can we commit a store
    val can_fire_store_commit = widthMap(w =>
        (stq_commit_e.valid &&
                !stq_commit_e.bits.uop.is_fence &&
                !mem_xcpt_valid &&
                !stq_commit_e.bits.uop.exception &&
                (w == 0).B &&
                (stq_commit_e.bits.committed || (stq_commit_e.bits.uop.is_amo &&
                        stq_commit_e.bits.addr.valid &&
                        !stq_commit_e.bits.addr_is_virtual &&
                        stq_commit_e.bits.data.valid))))

    // Can we wakeup a load that was nack'd
    val block_load_wakeup = WireInit(false.B)
    val can_fire_load_wakeup = widthMap(w =>
        (ldq_wakeup_e.valid &&
                ldq_wakeup_e.bits.addr.valid &&
                !ldq_wakeup_e.bits.succeeded &&
                !ldq_wakeup_e.bits.addr_is_virtual &&
                !ldq_wakeup_e.bits.executed &&
                !ldq_wakeup_e.bits.order_fail &&
                !p1_block_load_mask(ldq_wakeup_idx) &&
                !p2_block_load_mask(ldq_wakeup_idx) &&
                !store_needs_order &&
                !block_load_wakeup &&
                (w == memWidth - 1).B &&
                (/*!ldq_wakeup_e.bits.addr_is_uncacheable ||*/ (io.core.commit_load_at_rob_head &&
                        ldq_head === ldq_wakeup_idx &&
                        ldq_wakeup_e.bits.st_dep_mask.asUInt === 0.U))))

   //---------------------------------------------------------
    // Controller logic. Arbitrate which request actually fires

    val exe_tlb_valid = Wire(Vec(memWidth, Bool()))
    for (w <- 0 until memWidth) {
        var tlb_avail = true.B
        var dc_avail = true.B
        var lcam_avail = true.B
        var rob_avail = true.B

        def lsu_sched(can_fire: Bool, uses_tlb: Boolean, uses_dc: Boolean, uses_lcam: Boolean, uses_rob: Boolean): Bool = {
            val will_fire = can_fire && !(uses_tlb.B && !tlb_avail) &&
                    !(uses_lcam.B && !lcam_avail) &&
                    !(uses_dc.B && !dc_avail) &&
                    !(uses_rob.B && !rob_avail)
            tlb_avail = tlb_avail && !(will_fire && uses_tlb.B)
            lcam_avail = lcam_avail && !(will_fire && uses_lcam.B)
            dc_avail = dc_avail && !(will_fire && uses_dc.B)
            rob_avail = rob_avail && !(will_fire && uses_rob.B)
            dontTouch(will_fire) // dontTouch these so we can inspect the will_fire signals
            will_fire
        }
        //************* will开头表示经过了排序等
        // The order of these statements is the priority
        // Some restrictions
        //  - Incoming ops must get precedence, can't backpresure memaddrgen
        //  - Incoming hellacache ops must get precedence over retrying ops (PTW must get precedence over retrying translation)
        // Notes on performance
        //  - Prioritize releases, this speeds up cache line writebacks and refills
        //  - Store commits are lowest priority, since they don't "block" younger instructions unless stq fills up
        will_fire_load_incoming(w) := lsu_sched(can_fire_load_incoming(w), true, true, true, false) // TLB , DC , LCAM
        will_fire_stad_incoming(w) := lsu_sched(can_fire_stad_incoming(w), true, false, true, true) // TLB ,    , LCAM , ROB
        will_fire_sta_incoming(w) := lsu_sched(can_fire_sta_incoming(w), true, false, true, true) // TLB ,    , LCAM , ROB
        will_fire_std_incoming(w) := lsu_sched(can_fire_std_incoming(w), false, false, false, true) //                 , ROB
        will_fire_sfence(w) := lsu_sched(can_fire_sfence(w), true, false, false, true) // TLB ,    ,      , ROB
        will_fire_load_wakeup(w) := lsu_sched(can_fire_load_wakeup(w), false, true, true, false) //     , DC , LCAM1
        will_fire_store_commit(w) := lsu_sched(can_fire_store_commit(w), false, true, false, false) //     , DC


        assert(!(exe_req(w).valid && !(will_fire_load_incoming(w) || will_fire_stad_incoming(w) || will_fire_sta_incoming(w) || will_fire_std_incoming(w) || will_fire_sfence(w))))

        when(will_fire_load_wakeup(w)) {
            block_load_mask(ldq_wakeup_idx) := true.B
        }.elsewhen(will_fire_load_incoming(w)) {
            block_load_mask(exe_req(w).bits.uop.ldq_idx) := true.B
        }
        exe_tlb_valid(w) := !tlb_avail
    }
    assert((memWidth == 1).B ||
            (!(will_fire_sfence.reduce(_ || _) && !will_fire_sfence.reduce(_ && _)) &&
                    !will_fire_store_commit.reduce(_ && _) &&
                    !will_fire_load_wakeup.reduce(_ && _)),
        "Some operations is proceeding down multiple pipes")

    require(memWidth <= 2)

    //--------------------------------------------
    // TLB Access

    val exe_tlb_uop = widthMap(w =>
        Mux(will_fire_load_incoming(w) ||
                will_fire_stad_incoming(w) ||
                will_fire_sta_incoming(w) ||
                will_fire_sfence(w), exe_req(w).bits.uop,
                        NullMicroOp))

    val exe_tlb_vaddr = widthMap(w =>
        Mux(will_fire_load_incoming(w) ||
                will_fire_stad_incoming(w) ||
                will_fire_sta_incoming(w), exe_req(w).bits.addr,
            Mux(will_fire_sfence(w), exe_req(w).bits.sfence.bits.addr,
                            0.U)))

    val exe_sfence = WireInit((0.U).asTypeOf(Valid(new rocket.SFenceReq)))
    for (w <- 0 until memWidth) {
        when(will_fire_sfence(w)) {
            exe_sfence := exe_req(w).bits.sfence
        }
    }

    val exe_size = widthMap(w =>
        Mux(will_fire_load_incoming(w) ||
                will_fire_stad_incoming(w) ||
                will_fire_sta_incoming(w) ||
                will_fire_sfence(w) , exe_tlb_uop(w).mem_size,
                0.U))
    val exe_cmd = widthMap(w =>
        Mux(will_fire_load_incoming(w) ||
                will_fire_stad_incoming(w) ||
                will_fire_sta_incoming(w) ||
                will_fire_sfence(w) , exe_tlb_uop(w).mem_cmd,
                0.U))


    // exceptions
    val ma_ld = widthMap(w => will_fire_load_incoming(w) && exe_req(w).bits.mxcpt.valid) // We get ma_ld in memaddrcalc
    val ma_st = widthMap(w => (will_fire_sta_incoming(w) || will_fire_stad_incoming(w)) && exe_req(w).bits.mxcpt.valid) // We get ma_ld in memaddrcalc
//    val pf_ld = widthMap(w => dtlb.io.req(w).valid && dtlb.io.resp(w).pf.ld && exe_tlb_uop(w).uses_ldq)
//    val pf_st = widthMap(w => dtlb.io.req(w).valid && dtlb.io.resp(w).pf.st && exe_tlb_uop(w).uses_stq)
//    val ae_ld = widthMap(w => dtlb.io.req(w).valid && dtlb.io.resp(w).ae.ld && exe_tlb_uop(w).uses_ldq)
//    val ae_st = widthMap(w => dtlb.io.req(w).valid && dtlb.io.resp(w).ae.st && exe_tlb_uop(w).uses_stq)

    // TODO check for xcpt_if and verify that never happens on non-speculative instructions.
    val mem_xcpt_valids = RegNext(widthMap(w =>
        (ma_ld(w) || ma_st(w)) &&
                !io.core.exception &&
                !IsKilledByBranch(io.core.brupdate, exe_tlb_uop(w))))
    val mem_xcpt_uops = RegNext(widthMap(w => UpdateBrMask(io.core.brupdate, exe_tlb_uop(w))))
    val mem_xcpt_causes = RegNext(widthMap(w =>
        Mux(ma_ld(w), rocket.Causes.misaligned_load.U,
            Mux(ma_st(w), rocket.Causes.misaligned_store.U,
                            rocket.Causes.store_access.U))))
    val mem_xcpt_vaddrs = RegNext(exe_tlb_vaddr)

    for (w <- 0 until memWidth) {
//        assert(!(dtlb.io.req(w).valid && exe_tlb_uop(w).is_fence), "Fence is pretending to talk to the TLB")
//        assert(!((will_fire_load_incoming(w) || will_fire_sta_incoming(w) || will_fire_stad_incoming(w)) &&
//                exe_req(w).bits.mxcpt.valid && dtlb.io.req(w).valid &&
//                !(exe_tlb_uop(w).ctrl.is_load || exe_tlb_uop(w).ctrl.is_sta)),
//            "A uop that's not a load or store-address is throwing a memory exception.")
    }

    mem_xcpt_valid := mem_xcpt_valids.reduce(_ || _)
    mem_xcpt_cause := mem_xcpt_causes(0)
    mem_xcpt_uop := mem_xcpt_uops(0)
    mem_xcpt_vaddr := mem_xcpt_vaddrs(0)
    var xcpt_found = mem_xcpt_valids(0)
    var oldest_xcpt_rob_idx = mem_xcpt_uops(0).rob_idx
    for (w <- 1 until memWidth) {
        val is_older = WireInit(false.B)
        when(mem_xcpt_valids(w) &&
                (IsOlder(mem_xcpt_uops(w).rob_idx, oldest_xcpt_rob_idx, io.core.rob_head_idx) || !xcpt_found)) {
            is_older := true.B
            mem_xcpt_cause := mem_xcpt_causes(w)
            mem_xcpt_uop := mem_xcpt_uops(w)
            mem_xcpt_vaddr := mem_xcpt_vaddrs(w)
        }
        xcpt_found = xcpt_found || mem_xcpt_valids(w)
        oldest_xcpt_rob_idx = Mux(is_older, mem_xcpt_uops(w).rob_idx, oldest_xcpt_rob_idx)
    }

//    val exe_tlb_miss = widthMap(w => dtlb.io.req(w).valid && (dtlb.io.resp(w).miss || !dtlb.io.req(w).ready))
//    val exe_tlb_paddr = widthMap(w => Cat(dtlb.io.resp(w).paddr(paddrBits - 1, corePgIdxBits),
//        exe_tlb_vaddr(w)(corePgIdxBits - 1, 0)))
//    val exe_tlb_uncacheable = widthMap(w => !(dtlb.io.resp(w).cacheable))

    for (w <- 0 until memWidth) {
//        assert(exe_tlb_paddr(w) === dtlb.io.resp(w).paddr || exe_req(w).bits.sfence.valid, "[lsu] paddrs should match.")

        when(mem_xcpt_valids(w)) {
            assert(RegNext(will_fire_load_incoming(w) || will_fire_stad_incoming(w) || will_fire_sta_incoming(w)
                    ))
            // Technically only faulting AMOs need this
            assert(mem_xcpt_uops(w).uses_ldq ^ mem_xcpt_uops(w).uses_stq)
            when(mem_xcpt_uops(w).uses_ldq) {
                ldq(mem_xcpt_uops(w).ldq_idx).bits.uop.exception := true.B
            }
                    .otherwise {
                        stq(mem_xcpt_uops(w).stq_idx).bits.uop.exception := true.B
                    }
        }
    }



    //------------------------------
    // Issue Someting to Memory
    //
    // A memory op can come from many different places
    // The address either was freshly translated, or we are
    // reading a physical address from the LDQ,STQ, or the HellaCache adapter


    // defaults
    io.dmem.brupdate := io.core.brupdate
    io.dmem.exception := io.core.exception
    io.dmem.rob_head_idx := io.core.rob_head_idx

    val dmem_req = Wire(Vec(memWidth, Valid(new DCacheReq)))
    io.dmem.req.valid := dmem_req.map(_.valid).reduce(_ || _)
    io.dmem.req.bits := dmem_req
    val dmem_req_fire = widthMap(w => dmem_req(w).valid && io.dmem.req.fire)

    val s0_executing_loads = WireInit(VecInit((0 until numLdqEntries).map(x => false.B)))


    for (w <- 0 until memWidth) {
        dmem_req(w).valid := false.B
        dmem_req(w).bits.uop := NullMicroOp
        dmem_req(w).bits.addr := 0.U
        dmem_req(w).bits.data := 0.U

        io.dmem.s1_kill(w) := false.B

        when(will_fire_load_incoming(w)) {
//            dmem_req(w).valid := !exe_tlb_miss(w) && !exe_tlb_uncacheable(w)
//            dmem_req(w).bits.addr := exe_tlb_paddr(w)
//            dmem_req(w).bits.uop := exe_tlb_uop(w)
            dmem_req(w).valid := true.B
            dmem_req(w).bits.addr   := exe_req(w).bits.addr
            dmem_req(w).bits.uop    := exe_req(w).bits.uop
            s0_executing_loads(ldq_incoming_idx(w)) := dmem_req_fire(w)
            assert(!ldq_incoming_e(w).bits.executed)
        }.elsewhen(will_fire_store_commit(w)) {
            dmem_req(w).valid := true.B
            dmem_req(w).bits.addr := stq_commit_e.bits.addr.bits
            dmem_req(w).bits.data := (new StoreGen(
                stq_commit_e.bits.uop.mem_size, 0.U,
                stq_commit_e.bits.data.bits,
                coreDataBytes)).data
            dmem_req(w).bits.uop := stq_commit_e.bits.uop

            stq_execute_head := Mux(dmem_req_fire(w),
                WrapInc(stq_execute_head, numStqEntries),
                stq_execute_head)

            stq(stq_execute_head).bits.succeeded := false.B
        }.elsewhen(will_fire_load_wakeup(w)) {
            dmem_req(w).valid := true.B
            dmem_req(w).bits.addr := ldq_wakeup_e.bits.addr.bits
            dmem_req(w).bits.uop := ldq_wakeup_e.bits.uop

            s0_executing_loads(ldq_wakeup_idx) := dmem_req_fire(w)

            assert(!ldq_wakeup_e.bits.executed && !ldq_wakeup_e.bits.addr_is_virtual)
        }

        //-------------------------------------------------------------
        // Write Addr into the LAQ/SAQ
        when(will_fire_load_incoming(w)) {
            val ldq_idx = ldq_incoming_idx(w)
            //            ldq(ldq_idx).bits.addr.bits := Mux(exe_tlb_miss(w), exe_tlb_vaddr(w), exe_tlb_paddr(w))
            //            ldq(ldq_idx).bits.uop.pdst := exe_tlb_uop(w).pdst
            //            ldq(ldq_idx).bits.addr_is_virtual := exe_tlb_miss(w)
            //            ldq(ldq_idx).bits.addr_is_uncacheable := exe_tlb_uncacheable(w) && !exe_tlb_miss(w)
            ldq(ldq_idx).bits.addr.valid := true.B
            ldq(ldq_idx).bits.addr.bits  := exe_req(w).bits.addr
            ldq(ldq_idx).bits.uop.pdst  := exe_req(w).bits.uop.pdst
            ldq(ldq_idx).bits.addr_is_virtual := true.B

            assert(!(will_fire_load_incoming(w) && ldq_incoming_e(w).bits.addr.valid),
                "[lsu] Incoming load is overwriting a valid address")
        }

        when(will_fire_sta_incoming(w) || will_fire_stad_incoming(w)) {
//            val stq_idx = Mux(will_fire_sta_incoming(w) || will_fire_stad_incoming(w),
//                stq_incoming_idx(w), stq_retry_idx)
//
//            stq(stq_idx).bits.addr.valid := !pf_st(w) // Prevent AMOs from executing!
//            stq(stq_idx).bits.addr.bits := Mux(exe_tlb_miss(w), exe_tlb_vaddr(w), exe_tlb_paddr(w))
//            stq(stq_idx).bits.uop.pdst := exe_tlb_uop(w).pdst // Needed for AMOs
//            stq(stq_idx).bits.addr_is_virtual := exe_tlb_miss(w)
            val stq_idx = stq_incoming_idx(w)
            stq(stq_idx).bits.addr.valid := true.B
            stq(stq_idx).bits.addr.bits  := exe_req.bits.addr
            stq(stq_idx).bits.uop.pdst := exe_req.bits.uop.pdst
            stq(stq_idx).bits.addr_is_virtual := false.B

            assert(!(will_fire_sta_incoming(w) && stq_incoming_e(w).bits.addr.valid),
                "[lsu] Incoming store is overwriting a valid address")

        }

        //-------------------------------------------------------------
        // Write data into the STQ
        when(will_fire_std_incoming(w) || will_fire_stad_incoming(w)) {
            val sidx = stq_incoming_idx(w)
            stq(sidx).bits.data.valid := true.B
            stq(sidx).bits.data.bits := exe_req(w).bits.data
            assert(!(stq(sidx).bits.data.valid),
                "[lsu] Incoming store is overwriting a valid data entry")
        }
    }

    //-------------------------------------------------------------
    //-------------------------------------------------------------
    // Cache Access Cycle (Mem)
    //-------------------------------------------------------------
    //-------------------------------------------------------------
    // Note the DCache may not have accepted our request

    val exe_req_killed = widthMap(w => IsKilledByBranch(io.core.brupdate, exe_req(w).bits.uop))
    //***********************stdf_killed 预测错误
    val fired_load_incoming = widthMap(w => RegNext(will_fire_load_incoming(w) && !exe_req_killed(w)))
    val fired_stad_incoming = widthMap(w => RegNext(will_fire_stad_incoming(w) && !exe_req_killed(w)))
    val fired_sta_incoming = widthMap(w => RegNext(will_fire_sta_incoming(w) && !exe_req_killed(w)))
    val fired_std_incoming = widthMap(w => RegNext(will_fire_std_incoming(w) && !exe_req_killed(w)))
    val fired_sfence = RegNext(will_fire_sfence)
    val fired_store_commit = RegNext(will_fire_store_commit)
    val fired_load_wakeup = widthMap(w => RegNext(will_fire_load_wakeup(w) && !IsKilledByBranch(io.core.brupdate, ldq_wakeup_e.bits.uop)))

    val mem_incoming_uop = RegNext(widthMap(w => UpdateBrMask(io.core.brupdate, exe_req(w).bits.uop)))
    val mem_ldq_incoming_e = RegNext(widthMap(w => UpdateBrMask(io.core.brupdate, ldq_incoming_e(w))))
    val mem_stq_incoming_e = RegNext(widthMap(w => UpdateBrMask(io.core.brupdate, stq_incoming_e(w))))
    val mem_ldq_wakeup_e = RegNext(UpdateBrMask(io.core.brupdate, ldq_wakeup_e))
    val mem_ldq_e = widthMap(w =>
        Mux(fired_load_incoming(w), mem_ldq_incoming_e(w),
                Mux(fired_load_wakeup(w), mem_ldq_wakeup_e, (0.U).asTypeOf(Valid(new LDQEntry)))))
    val mem_stq_e = widthMap(w =>
        Mux(fired_stad_incoming(w) ||
                fired_sta_incoming(w), mem_stq_incoming_e(w),
           (0.U).asTypeOf(Valid(new STQEntry))))

    val mem_paddr = RegNext(widthMap(w => dmem_req(w).bits.addr))

    // Task 1: Clr ROB busy bit
    val clr_bsy_valid = RegInit(widthMap(w => false.B))
    val clr_bsy_rob_idx = Reg(Vec(memWidth, UInt(robAddrSz.W)))
    val clr_bsy_brmask = Reg(Vec(memWidth, UInt(maxBrCount.W)))

    for (w <- 0 until memWidth) {
        clr_bsy_valid(w) := false.B
        clr_bsy_rob_idx(w) := 0.U
        clr_bsy_brmask(w) := 0.U


        when(fired_stad_incoming(w)) {
            clr_bsy_valid(w) := mem_stq_incoming_e(w).valid &&
                    !mem_stq_incoming_e(w).bits.uop.is_amo &&
                    !IsKilledByBranch(io.core.brupdate, mem_stq_incoming_e(w).bits.uop)
            clr_bsy_rob_idx(w) := mem_stq_incoming_e(w).bits.uop.rob_idx
            clr_bsy_brmask(w) := GetNewBrMask(io.core.brupdate, mem_stq_incoming_e(w).bits.uop)
        }.elsewhen(fired_sta_incoming(w)) {
            clr_bsy_valid(w) := mem_stq_incoming_e(w).valid &&
                    mem_stq_incoming_e(w).bits.data.valid &&
//                    !mem_tlb_miss(w) &&
                    !mem_stq_incoming_e(w).bits.uop.is_amo &&
                    !IsKilledByBranch(io.core.brupdate, mem_stq_incoming_e(w).bits.uop)
            clr_bsy_rob_idx(w) := mem_stq_incoming_e(w).bits.uop.rob_idx
            clr_bsy_brmask(w) := GetNewBrMask(io.core.brupdate, mem_stq_incoming_e(w).bits.uop)
        }.elsewhen(fired_std_incoming(w)) {
            clr_bsy_valid(w) := mem_stq_incoming_e(w).valid &&
                    mem_stq_incoming_e(w).bits.addr.valid &&
                    !mem_stq_incoming_e(w).bits.addr_is_virtual &&
                    !mem_stq_incoming_e(w).bits.uop.is_amo &&
                    !IsKilledByBranch(io.core.brupdate, mem_stq_incoming_e(w).bits.uop)
            clr_bsy_rob_idx(w) := mem_stq_incoming_e(w).bits.uop.rob_idx
            clr_bsy_brmask(w) := GetNewBrMask(io.core.brupdate, mem_stq_incoming_e(w).bits.uop)
        }.elsewhen(fired_sfence(w)) {
            clr_bsy_valid(w) := (w == 0).B // SFence proceeds down all paths, only allow one to clr the rob
            clr_bsy_rob_idx(w) := mem_incoming_uop(w).rob_idx
            clr_bsy_brmask(w) := GetNewBrMask(io.core.brupdate, mem_incoming_uop(w))
        }

        io.core.clr_bsy(w).valid := clr_bsy_valid(w) &&
                !IsKilledByBranch(io.core.brupdate, clr_bsy_brmask(w)) &&
                !io.core.exception && !RegNext(io.core.exception) && !RegNext(RegNext(io.core.exception))
        io.core.clr_bsy(w).bits := clr_bsy_rob_idx(w)
    }




    // Task 2: Do LD-LD. ST-LD searches for ordering failures
    //         Do LD-ST search for forwarding opportunities
    // We have the opportunity to kill a request we sent last cycle. Use it wisely!

    // We translated a store last cycle
    val do_st_search = widthMap(w => (fired_stad_incoming(w) || fired_sta_incoming(w) || fired_sta_retry(w)) && !mem_tlb_miss(w))
    // We translated a load last cycle
    val do_ld_search = widthMap(w => ((fired_load_incoming(w) && !mem_tlb_miss(w)) ||
            fired_load_wakeup(w)))
    // We are making a local line visible to other harts

    // Store addrs don't go to memory yet, get it from the TLB response
    // Load wakeups don't go through TLB, get it through memory
    // Load incoming and load retries go through both

    val lcam_addr = widthMap(w => Mux(fired_stad_incoming(w) || fired_sta_incoming(w),
        RegNext(exe_req(w).bits.addr),mem_paddr(w)))
    val lcam_uop = widthMap(w => Mux(do_st_search(w), mem_stq_e(w).bits.uop,
        Mux(do_ld_search(w), mem_ldq_e(w).bits.uop, NullMicroOp)))

    val lcam_mask = widthMap(w => GenByteMask(lcam_addr(w), lcam_uop(w).mem_size))
    val lcam_st_dep_mask = widthMap(w => mem_ldq_e(w).bits.st_dep_mask)
    val lcam_ldq_idx = widthMap(w =>
        Mux(fired_load_incoming(w), mem_incoming_uop(w).ldq_idx,
            Mux(fired_load_wakeup(w), RegNext(ldq_wakeup_idx), 0.U)))
    val lcam_stq_idx = widthMap(w =>
        Mux(fired_stad_incoming(w) ||
                fired_sta_incoming(w), mem_incoming_uop(w).stq_idx, 0.U))

//    val can_forward = WireInit(widthMap(w =>
//        Mux(fired_load_incoming(w) || fired_load_retry(w), !mem_tlb_uncacheable(w),
//            !ldq(lcam_ldq_idx(w)).bits.addr_is_uncacheable)))
    val can_forward = WireInit(widthMap(w => true.B))

    // Mask of stores which we conflict on address with
    val ldst_addr_matches = WireInit(widthMap(w => VecInit((0 until numStqEntries).map(x => false.B))))
    // Mask of stores which we can forward from
    val ldst_forward_matches = WireInit(widthMap(w => VecInit((0 until numStqEntries).map(x => false.B))))

    val failed_loads = WireInit(VecInit((0 until numLdqEntries).map(x => false.B))) // Loads which we will report as failures (throws a mini-exception)
    val nacking_loads = WireInit(VecInit((0 until numLdqEntries).map(x => false.B))) // Loads which are being nacked by dcache in the next stage

    val s1_executing_loads = RegNext(s0_executing_loads)
    val s1_set_execute = WireInit(s1_executing_loads)

    val mem_forward_valid = Wire(Vec(memWidth, Bool()))
    val mem_forward_ldq_idx = lcam_ldq_idx
    val mem_forward_ld_addr = lcam_addr
    val mem_forward_stq_idx = Wire(Vec(memWidth, UInt(log2Ceil(numStqEntries).W)))

    val wb_forward_valid = RegNext(mem_forward_valid)
    val wb_forward_ldq_idx = RegNext(mem_forward_ldq_idx)
    val wb_forward_ld_addr = RegNext(mem_forward_ld_addr)
    val wb_forward_stq_idx = RegNext(mem_forward_stq_idx)

    for (i <- 0 until numLdqEntries) {
        val l_valid = ldq(i).valid
        val l_bits = ldq(i).bits
        val l_addr = ldq(i).bits.addr.bits
        val l_mask = GenByteMask(l_addr, l_bits.uop.mem_size)

        val l_forwarders = widthMap(w => wb_forward_valid(w) && wb_forward_ldq_idx(w) === i.U)
        val l_is_forwarding = l_forwarders.reduce(_ || _)
        val l_forward_stq_idx = Mux(l_is_forwarding, Mux1H(l_forwarders, wb_forward_stq_idx), l_bits.forward_stq_idx)


        val block_addr_matches = widthMap(w => lcam_addr(w) >> blockOffBits === l_addr >> blockOffBits)
        val word_addr_matches = widthMap(w => block_addr_matches(w) && lcam_addr(w)(blockOffBits - 1, 2) === l_addr(blockOffBits - 1, 2))
        val mask_match = widthMap(w => (l_mask & lcam_mask(w)) === l_mask)
        val mask_overlap = widthMap(w => (l_mask & lcam_mask(w)).orR)

        // Searcher is a store
        for (w <- 0 until memWidth) {
            //Victim Buffer
           when(do_st_search(w)                                                                                     &&
                    l_valid &&
                    l_bits.addr.valid &&
                    (l_bits.executed || l_bits.succeeded || l_is_forwarding) &&
                    !l_bits.addr_is_virtual &&
                    l_bits.st_dep_mask(lcam_stq_idx(w)) &&
                    word_addr_matches(w)                                                                             &&
            mask_overlap(w)
            )
            { //有字节重叠

                val forwarded_is_older = IsOlder(l_forward_stq_idx, lcam_stq_idx(w), l_bits.youngest_stq_idx)
                // We are older than this load, which overlapped us.
                when(!l_bits.forward_std_val || // If the load wasn't forwarded, it definitely failed，因为已经执行过了
                        ((l_forward_stq_idx =/= lcam_stq_idx(w)) && forwarded_is_older)) { // If the load forwarded from us, we might be ok
                    ldq(i).bits.order_fail := true.B
                    failed_loads(i) := true.B
                }
            }
        }
    }

    for (i <- 0 until numStqEntries) {
        val s_addr = stq(i).bits.addr.bits
        val s_uop = stq(i).bits.uop
        val word_addr_matches = widthMap(w =>
            (stq(i).bits.addr.valid &&
                    !stq(i).bits.addr_is_virtual &&
                    (s_addr(corePAddrBits - 1, 2) === lcam_addr(w)(corePAddrBits - 1, 2))))
        val write_mask = GenByteMask(s_addr, s_uop.mem_size)
        for (w <- 0 until memWidth) {
            when(do_ld_search(w) && stq(i).valid && lcam_st_dep_mask(w)(i)) {
                when(((lcam_mask(w) & write_mask) === lcam_mask(w)) && !s_uop.is_fence && wword_addr_matches(w) && can_forward(w)) {
                    ldst_addr_matches(w)(i) := true.B
                    ldst_forward_matches(w)(i) := true.B
                    io.dmem.s1_kill(w) := RegNext(dmem_req_fire(w))
                    s1_set_execute(lcam_ldq_idx(w)) := false.B
                }
                        .elsewhen(((lcam_mask(w) & write_mask) =/= 0.U) && word_addr_matches(w)) {
                            ldst_addr_matches(w)(i) := true.B
                            io.dmem.s1_kill(w) := RegNext(dmem_req_fire(w))
                            s1_set_execute(lcam_ldq_idx(w)) := false.B
                        }
                        .elsewhen(s_uop.is_fence || s_uop.is_amo) {
                            ldst_addr_matches(w)(i) := true.B
                            io.dmem.s1_kill(w) := RegNext(dmem_req_fire(w))
                            s1_set_execute(lcam_ldq_idx(w)) := false.B
                        }
            }
        }
    }

    // Set execute bit in LDQ
    for (i <- 0 until numLdqEntries) {
        when(s1_set_execute(i)) {
            ldq(i).bits.executed := true.B
        }
    }

    // Find the youngest store which the load is dependent on
    val forwarding_age_logic = Seq.fill(memWidth) {
        Module(new ForwardingAgeLogic(numStqEntries))
    }
    for (w <- 0 until memWidth) {
        forwarding_age_logic(w).io.addr_matches := ldst_addr_matches(w).asUInt
        forwarding_age_logic(w).io.youngest_st_idx := lcam_uop(w).stq_idx
    }
    val forwarding_idx = widthMap(w => forwarding_age_logic(w).io.forwarding_idx)

    // Forward if st-ld forwarding is possible from the writemask and loadmask
    mem_forward_valid := widthMap(w =>
        (ldst_forward_matches(w)(forwarding_idx(w)) &&
                !IsKilledByBranch(io.core.brupdate, lcam_uop(w)) &&
                !io.core.exception && !RegNext(io.core.exception)))
    mem_forward_stq_idx := forwarding_idx
    //********?
    // Avoid deadlock with a 1-w LSU prioritizing load wakeups > store commits
    // On a 2W machine, load wakeups and store commits occupy separate pipelines,
    // so only add this logic for 1-w LSU
    if (memWidth == 1) {
        // Wakeups may repeatedly find a st->ld addr conflict and fail to forward,
        // repeated wakeups may block the store from ever committing
        // Disallow load wakeups 1 cycle after this happens to allow the stores to drain
        when(RegNext(ldst_addr_matches(0).reduce(_ || _) && !mem_forward_valid(0))) {
            block_load_wakeup := true.B
        }

        // If stores remain blocked for 15 cycles, block load wakeups to get a store through
        val store_blocked_counter = Reg(UInt(4.W))
        when(will_fire_store_commit(0) || !can_fire_store_commit(0)) {
            store_blocked_counter := 0.U
        }.elsewhen(can_fire_store_commit(0) && !will_fire_store_commit(0)) {
            store_blocked_counter := Mux(store_blocked_counter === 15.U, 15.U, store_blocked_counter + 1.U)
        }
        when(store_blocked_counter === 15.U) {
            block_load_wakeup := true.B
        }
    }


    // Task 3: Clr unsafe bit in ROB for succesful translations
    //         Delay this a cycle to avoid going ahead of the exception broadcast
    //         The unsafe bit is cleared on the first translation, so no need to fire for load wakeups
    for (w <- 0 until memWidth) {
        io.core.clr_unsafe(w).valid := RegNext((do_st_search(w) || do_ld_search(w)) && !fired_load_wakeup(w)) && false.B
        io.core.clr_unsafe(w).bits := RegNext(lcam_uop(w).rob_idx)
    }

    // detect which loads get marked as failures, but broadcast to the ROB the oldest failing load
    // TODO encapsulate this in an age-based  priority-encoder
    //   val l_idx = AgePriorityEncoder((Vec(Vec.tabulate(numLdqEntries)(i => failed_loads(i) && i.U >= laq_head)
    //   ++ failed_loads)).asUInt)
    val temp_bits = (VecInit(VecInit.tabulate(numLdqEntries)(i =>
        failed_loads(i) && i.U >= ldq_head) ++ failed_loads)).asUInt
    val l_idx = PriorityEncoder(temp_bits)

    // one exception port, but multiple causes!
    // - 1) the incoming store-address finds a faulting load (it is by definition younger)
    // - 2) the incoming load or store address is excepting. It must be older and thus takes precedent.
    val r_xcpt_valid = RegInit(false.B)
    val r_xcpt = Reg(new Exception)

    val ld_xcpt_valid = failed_loads.reduce(_ | _)
    val ld_xcpt_uop = ldq(Mux(l_idx >= numLdqEntries.U, l_idx - numLdqEntries.U, l_idx)).bits.uop

    val use_mem_xcpt = (mem_xcpt_valid && IsOlder(mem_xcpt_uop.rob_idx, ld_xcpt_uop.rob_idx, io.core.rob_head_idx)) || !ld_xcpt_valid

    val xcpt_uop = Mux(use_mem_xcpt, mem_xcpt_uop, ld_xcpt_uop)

    r_xcpt_valid := (ld_xcpt_valid || mem_xcpt_valid) &&
            !io.core.exception &&
            !IsKilledByBranch(io.core.brupdate, xcpt_uop)
    r_xcpt.uop := xcpt_uop
    r_xcpt.uop.br_mask := GetNewBrMask(io.core.brupdate, xcpt_uop)
    r_xcpt.cause := Mux(use_mem_xcpt, mem_xcpt_cause, MINI_EXCEPTION_MEM_ORDERING)
    r_xcpt.badvaddr := mem_xcpt_vaddr // TODO is there another register we can use instead?

    io.core.lxcpt.valid := r_xcpt_valid && !io.core.exception && !IsKilledByBranch(io.core.brupdate, r_xcpt.uop)
    io.core.lxcpt.bits := r_xcpt

    // Task 4: Speculatively wakeup loads 1 cycle before they come back
    for (w <- 0 until memWidth) {
        io.core.spec_ld_wakeup(w).valid :=
                fired_load_incoming(w) &&
                mem_incoming_uop(w).pdst =/= 0.U
        io.core.spec_ld_wakeup(w).bits := mem_incoming_uop(w).pdst
    }


    //-------------------------------------------------------------
    //-------------------------------------------------------------
    // Writeback Cycle (St->Ld Forwarding Path)
    //-------------------------------------------------------------
    //-------------------------------------------------------------

    // Handle Memory Responses and nacks
    //----------------------------------
    for (w <- 0 until memWidth) {
        io.core.exe(w).iresp.valid := false.B
    }

    val dmem_resp_fired = WireInit(widthMap(w => false.B))

    for (w <- 0 until memWidth) {
        // Handle nacks
        when(io.dmem.nack(w).valid) {
            // We have to re-execute this!
                    when(io.dmem.nack(w).bits.uop.uses_ldq) {
                        assert(ldq(io.dmem.nack(w).bits.uop.ldq_idx).bits.executed)
                        ldq(io.dmem.nack(w).bits.uop.ldq_idx).bits.executed := false.B
                        nacking_loads(io.dmem.nack(w).bits.uop.ldq_idx) := true.B
                    }
                    .otherwise {
                        assert(io.dmem.nack(w).bits.uop.uses_stq)
                        when(IsOlder(io.dmem.nack(w).bits.uop.stq_idx, stq_execute_head, stq_head)) {
                            stq_execute_head := io.dmem.nack(w).bits.uop.stq_idx
                        }
                    }
        }
        // Handle the response
        when(io.dmem.resp(w).valid) {
            when(io.dmem.resp(w).bits.uop.uses_ldq) {
                val ldq_idx = io.dmem.resp(w).bits.uop.ldq_idx
                val send_iresp = ldq(ldq_idx).bits.uop.dst_rtype === RT_FIX

                io.core.exe(w).iresp.bits.uop := ldq(ldq_idx).bits.uop
                io.core.exe(w).iresp.valid := send_iresp
                io.core.exe(w).iresp.bits.data := io.dmem.resp(w).bits.data

                dmem_resp_fired(w) := true.B

                ldq(ldq_idx).bits.succeeded := io.core.exe(w).iresp.valid
            }
                    .elsewhen(io.dmem.resp(w).bits.uop.uses_stq) {
                        stq(io.dmem.resp(w).bits.uop.stq_idx).bits.succeeded := true.B
                        when(io.dmem.resp(w).bits.uop.is_amo) {
                            dmem_resp_fired(w) := true.B
                            io.core.exe(w).iresp.valid := true.B
                            io.core.exe(w).iresp.bits.uop := stq(io.dmem.resp(w).bits.uop.stq_idx).bits.uop
                            io.core.exe(w).iresp.bits.data := io.dmem.resp(w).bits.data
                        }
                    }
        }


        when(dmem_resp_fired(w) && wb_forward_valid(w)) {
            // Twiddle thumbs. Can't forward because dcache response takes precedence
        }
                .elsewhen(!dmem_resp_fired(w) && wb_forward_valid(w)) {
                    val f_idx = wb_forward_ldq_idx(w)
                    val forward_uop = ldq(f_idx).bits.uop
                    val stq_e = stq(wb_forward_stq_idx(w))
                    val data_ready = stq_e.bits.data.valid
                    val live = !IsKilledByBranch(io.core.brupdate, forward_uop)
                    val storegen = StoreGen(
                        stq_e.bits.uop.mem_size, stq_e.bits.addr.bits,
                        stq_e.bits.data.bits, xLen/8)
                    val loadgen = LoadGen(
                        forward_uop.mem_size, forward_uop.mem_signed,
                        wb_forward_ld_addr(w),
                        storegen.data, false.B, xLen/8)

                    io.core.exe(w).iresp.valid := (forward_uop.dst_rtype === RT_FIX) && data_ready && live
                    io.core.exe(w).iresp.bits.uop := forward_uop
                    io.core.exe(w).iresp.bits.data := loadgen.data

                    when(data_ready && live) {
                        ldq(f_idx).bits.succeeded := data_ready
                        ldq(f_idx).bits.forward_std_val := true.B
                        ldq(f_idx).bits.forward_stq_idx := wb_forward_stq_idx(w)
                    }
                }
    }

    // Initially assume the speculative load wakeup failed
    io.core.ld_miss := RegNext(io.core.spec_ld_wakeup.map(_.valid).reduce(_ || _))
    val spec_ld_succeed = widthMap(w =>
        !RegNext(io.core.spec_ld_wakeup(w).valid) ||
                (io.core.exe(w).iresp.valid &&
                        io.core.exe(w).iresp.bits.uop.ldq_idx === RegNext(mem_incoming_uop(w).ldq_idx)
                        )
    ).reduce(_ && _)
    when(spec_ld_succeed) {
        io.core.ld_miss := false.B
    }


    //-------------------------------------------------------------
    // Kill speculated entries on branch mispredict
    //-------------------------------------------------------------
    //-------------------------------------------------------------

    // Kill stores
    val st_brkilled_mask = Wire(Vec(numStqEntries, Bool()))
    for (i <- 0 until numStqEntries) {
        st_brkilled_mask(i) := false.B

        when(stq(i).valid) {
            stq(i).bits.uop.brMask := GetNewBrMask(io.core.brupdate, stq(i).bits.uop.brMask) //下一周期才会更新

            when(IsKilledByBranch(io.core.brupdate, stq(i).bits.uop)) {
                stq(i).valid := false.B
                stq(i).bits.addr.valid := false.B
                stq(i).bits.data.valid := false.B
                st_brkilled_mask(i) := true.B
            }
        }

        assert(!(IsKilledByBranch(io.core.brupdate, stq(i).bits.uop) && stq(i).valid && stq(i).bits.committed),
            "Branch is trying to clear a committed store.")
    }

    // Kill loads
    for (i <- 0 until numLdqEntries) {
        when(ldq(i).valid) {
            ldq(i).bits.uop.brMask := GetNewBrMask(io.core.brupdate, ldq(i).bits.uop.brMask)
            when(IsKilledByBranch(io.core.brupdate, ldq(i).bits.uop)) {
                ldq(i).valid := false.B
                ldq(i).bits.addr.valid := false.B
            }
        }
    }

    //-------------------------------------------------------------
    when(io.core.brupdate.b2.mispredict && !io.core.exception) {
        stq_tail := io.core.brupdate.b2.uop.stq_idx
        ldq_tail := io.core.brupdate.b2.uop.ldq_idx
    }

    //-------------------------------------------------------------
    //-------------------------------------------------------------
    // dequeue old entries on commit
    //-------------------------------------------------------------
    //-------------------------------------------------------------

    var temp_stq_commit_head = stq_commit_head
    var temp_ldq_head = ldq_head
    for (w <- 0 until coreWidth) {
        val commit_store = io.core.commit.valids(w) && io.core.commit.uops(w).uses_stq
        val commit_load = io.core.commit.valids(w) && io.core.commit.uops(w).uses_ldq
        val idx = Mux(commit_store, temp_stq_commit_head, temp_ldq_head)
        when(commit_store) {
            stq(idx).bits.committed := true.B
        }.elsewhen(commit_load) {
            assert(ldq(idx).valid, "[lsu] trying to commit an un-allocated load entry.")
            assert((ldq(idx).bits.executed || ldq(idx).bits.forward_std_val) && ldq(idx).bits.succeeded,
                "[lsu] trying to commit an un-executed load entry.")

            ldq(idx).valid := false.B
            ldq(idx).bits.addr.valid := false.B
            ldq(idx).bits.executed := false.B
            ldq(idx).bits.succeeded := false.B
            ldq(idx).bits.order_fail := false.B
            ldq(idx).bits.forward_std_val := false.B

        }

        temp_stq_commit_head = Mux(commit_store,
            WrapInc(temp_stq_commit_head, numStqEntries),
            temp_stq_commit_head)

        temp_ldq_head = Mux(commit_load,
            WrapInc(temp_ldq_head, numLdqEntries),
            temp_ldq_head)
    }
    stq_commit_head := temp_stq_commit_head
    ldq_head := temp_ldq_head

    // store has been committed AND successfully sent data to memory
    when(stq(stq_head).valid && stq(stq_head).bits.committed) {
        when(stq(stq_head).bits.uop.is_fence && !io.dmem.ordered) {
            io.dmem.force_order := true.B
            store_needs_order := true.B
        }
        clear_store := Mux(stq(stq_head).bits.uop.is_fence, io.dmem.ordered,
            stq(stq_head).bits.succeeded)
    }

    when(clear_store) {
        stq(stq_head).valid := false.B
        stq(stq_head).bits.addr.valid := false.B
        stq(stq_head).bits.data.valid := false.B
        stq(stq_head).bits.succeeded := false.B
        stq(stq_head).bits.committed := false.B

        stq_head := WrapInc(stq_head, numStqEntries)
        when(stq(stq_head).bits.uop.is_fence) {
            stq_execute_head := WrapInc(stq_execute_head, numStqEntries)
        }
    }




    //-------------------------------------------------------------
    // Exception / Reset

    // for the live_store_mask, need to kill stores that haven't been committed
    val st_exc_killed_mask = WireInit(VecInit((0 until numStqEntries).map(x => false.B)))

    when(reset.asBool || io.core.exception) {
        ldq_head := 0.U
        ldq_tail := 0.U

        when(reset.asBool) {
            stq_head := 0.U
            stq_tail := 0.U
            stq_commit_head := 0.U
            stq_execute_head := 0.U

            for (i <- 0 until numStqEntries) {
                stq(i).valid := false.B
                stq(i).bits.addr.valid := false.B
                stq(i).bits.data.valid := false.B
                stq(i).bits.uop := NullMicroOp
            }
        }
                .otherwise // exception
                {
                    stq_tail := stq_commit_head

                    for (i <- 0 until numStqEntries) {
                        when(!stq(i).bits.committed && !stq(i).bits.succeeded) {
                            stq(i).valid := false.B
                            stq(i).bits.addr.valid := false.B
                            stq(i).bits.data.valid := false.B
                            st_exc_killed_mask(i) := true.B
                        }
                    }
                }

        for (i <- 0 until numLdqEntries) {
            ldq(i).valid := false.B
            ldq(i).bits.addr.valid := false.B
            ldq(i).bits.executed := false.B
        }
    }

    //-------------------------------------------------------------
    // Live Store Mask
    // track a bit-array of stores that are alive
    // (could maybe be re-produced from the stq_head/stq_tail, but need to know include spec_killed entries)

    // TODO is this the most efficient way to compute the live store mask?
    live_store_mask := next_live_store_mask &
            ~(st_brkilled_mask.asUInt) &
            ~(st_exc_killed_mask.asUInt)


}

// -------------------------------------Utils----------------------------------------

object GenByteMask {
    def apply(addr: UInt, size: UInt): UInt = {
        val mask = Wire(UInt(4.W))
        mask := MuxCase(15.U(4.W), Array(
            (size === 0.U) -> (1.U(4.W) << addr(1, 0)),
            (size === 1.U) -> (3.U(4.W) << (addr(1) << 1.U)),
            (size === 2.U) -> 15.U(4.W)))
        mask
    }
}

class ForwardingAgeLogic(num_entries: Int) extends CoreModule{
    val io = IO(new Bundle {
        val addr_matches = Input(UInt(num_entries.W)) // bit vector of addresses that match
        // between the load and the SAQ
        val youngest_st_idx = Input(UInt(stqAddrSz.W)) // needed to get "age"

        val forwarding_val = Output(Bool())
        val forwarding_idx = Output(UInt(stqAddrSz.W))
    })

    // generating mask that zeroes out anything younger than tail
    val age_mask = Wire(Vec(num_entries, Bool()))
    for (i <- 0 until num_entries) {
        age_mask(i) := true.B
        when(i.U >= io.youngest_st_idx) // currently the tail points PAST last store, so use >=
        {
            age_mask(i) := false.B
        }
    }

    // Priority encoder with moving tail: double length
    val matches = Wire(UInt((2 * num_entries).W))
    matches := Cat(io.addr_matches & age_mask.asUInt,
        io.addr_matches)

    val found_match = Wire(Bool())
    found_match := false.B
    io.forwarding_idx := 0.U

    // look for youngest, approach from the oldest side, let the last one found stick
    for (i <- 0 until (2 * num_entries)) {
        when(matches(i)) {
            found_match := true.B
            io.forwarding_idx := (i % num_entries).U
        }
    }

    io.forwarding_val := found_match
}

class StoreGen(typ: UInt, addr: UInt, dat: UInt, maxSize: Int) {
    val size = typ(log2Up(log2Up(maxSize)+1)-1,0)
    def misaligned: Bool =
        (addr & ((1.U << size) - 1.U)(log2Up(maxSize)-1,0)).orR

    def mask = {
        var res = 1.U
        for (i <- 0 until log2Up(maxSize)) {
            val upper = Mux(addr(i), res, 0.U) | Mux(size >= (i+1).U, ((BigInt(1) << (1 << i))-1).U, 0.U)
            val lower = Mux(addr(i), 0.U, res)
            res = Cat(upper, lower)
        }
        res
    }

    protected def genData(i: Int): UInt =
        if (i >= log2Up(maxSize)) dat
        else Mux(size === i.U, Fill(1 << (log2Up(maxSize)-i), dat((8 << i)-1,0)), genData(i+1))

    def data = genData(0)
    def wordData = genData(2)
}

class LoadGen(typ: UInt, signed: Bool, addr: UInt, dat: UInt, zero: Bool, maxSize: Int) {
    private val size = new StoreGen(typ, addr, dat, maxSize).size

    private def genData(logMinSize: Int): UInt = {
        var res = dat
        for (i <- log2Up(maxSize)-1 to logMinSize by -1) {
            val pos = 8 << i
            val shifted = Mux(addr(i), res(2*pos-1,pos), res(pos-1,0))
            val doZero = (i == 0).B && zero
            val zeroed = Mux(doZero, 0.U, shifted)
            res = Cat(Mux(size === i.U || doZero, Fill(8*maxSize-pos, signed && zeroed(pos-1)), res(8*maxSize-1,pos)), zeroed)
        }
        res
    }

    def wordData = genData(2)
    def data = genData(0)
}

object maskMatch {
    def apply(msk1: UInt, msk2: UInt): Bool = (msk1 & msk2) =/= 0.U
}

object GetNewBrMask {
    def apply(brupdate: BrUpdateInfo, uop: MicroOp): UInt = {
        return uop.brMask & ~brupdate.b1.resolve_mask
    }

    def apply(brupdate: BrUpdateInfo, br_mask: UInt): UInt = {
        return br_mask & ~brupdate.b1.resolve_mask
    }
}

object UpdateBrMask {
    def apply(brupdate: BrUpdateInfo, uop: MicroOp): MicroOp = {
        val out = WireInit(uop)
        out.brMask := GetNewBrMask(brupdate, uop)
        out
    }
    def apply[T <: boom.common.HasBoomUOP](brupdate: BrUpdateInfo, bundle: T): T = {
        val out = WireInit(bundle)
        out.uop.brMmask := GetNewBrMask(brupdate, bundle.uop.brMask)
        out
    }
    def apply[T <: boom.common.HasBoomUOP](brupdate: BrUpdateInfo, bundle: Valid[T]): Valid[T] = {
        val out = WireInit(bundle)
        out.bits.uop.brMask := GetNewBrMask(brupdate, bundle.bits.uop.brMask)
        out.valid := bundle.valid && !IsKilledByBranch(brupdate, bundle.bits.uop.brMask)
        out
    }
}