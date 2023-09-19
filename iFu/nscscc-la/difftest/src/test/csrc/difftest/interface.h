/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

/**
 * Headers for Verilog DPI-C difftest interface
 */

#ifndef __DT_INTERFACE_H__
#define __DT_INTERFACE_H__

#include "difftest.h"

// #ifdef __cplusplus
// extern "C" {
// #endif

#define DIFFTEST_DPIC_FUNC_NAME(name) \
  v_difftest_##name

#define DIFFTEST_DPIC_FUNC_DECL(name) \
  extern "C" void DIFFTEST_DPIC_FUNC_NAME(name)

#define DPIC_ARG_BIT  uint8_t
#define DPIC_ARG_BYTE uint8_t
#define DPIC_ARG_INT  uint32_t
// #define DPIC_ARG_INT uint64_t

// #define DPIC_ARG_BIT  svBit
// #define DPIC_ARG_BYTE char
// #define DPIC_ARG_INT  int32_t
// #define DPIC_ARG_INT int64_t

// v_difftest_init
extern "C" int v_difftest_init();
extern "C" int v_difftest_step();

// v_difftest_step
// extern "C" int 
// #define INTERFACE_STEP                   
//   DIFFTEST_DPIC_FUNC_DECL(step) (        
//   )

// v_difftest_ArchEvent
/* #define INTERFACE_ARCH_EVENT             \
  DIFFTEST_DPIC_FUNC_DECL(ArchEvent) (   \
    DPIC_ARG_BYTE coreid,                \
    DPIC_ARG_INT  intrNo,                \
    DPIC_ARG_INT  cause,                 \
    DPIC_ARG_INT exceptionPC            \
  ) */

// v_difftest_InstrCommit
#define INTERFACE_INSTR_COMMIT           \
  DIFFTEST_DPIC_FUNC_DECL(InstrCommit) ( \
    DPIC_ARG_BYTE coreid,                \
    DPIC_ARG_BYTE index,                 \
    DPIC_ARG_BIT  valid,                 \
    DPIC_ARG_INT pc,                    \
    DPIC_ARG_INT  instr,                 \
    DPIC_ARG_BIT  skip,                  \
    DPIC_ARG_BIT  wen,                   \
    DPIC_ARG_BYTE wdest,                 \
    DPIC_ARG_INT wdata                  \
  )

// v_difftest_TrapEvent
#define INTERFACE_TRAP_EVENT             \
  DIFFTEST_DPIC_FUNC_DECL(TrapEvent) (   \
    DPIC_ARG_BYTE coreid,                \
    DPIC_ARG_BIT  valid,                 \
    DPIC_ARG_BYTE code,                  \
    DPIC_ARG_INT pc,                    \
    DPIC_ARG_INT cycleCnt,              \
    DPIC_ARG_INT instrCnt               \
  )

// v_difftest_CSRState
/* #define INTERFACE_CSR_STATE              \
  DIFFTEST_DPIC_FUNC_DECL(CSRState) (    \
    DPIC_ARG_BYTE coreid,                \
    DPIC_ARG_BYTE priviledgeMode,        \
    DPIC_ARG_INT mstatus,               \
    DPIC_ARG_INT sstatus,               \
    DPIC_ARG_INT mepc,                  \
    DPIC_ARG_INT sepc,                  \
    DPIC_ARG_INT mtval,                 \
    DPIC_ARG_INT stval,                 \
    DPIC_ARG_INT mtvec,                 \
    DPIC_ARG_INT stvec,                 \
    DPIC_ARG_INT mcause,                \
    DPIC_ARG_INT scause,                \
    DPIC_ARG_INT satp,                  \
    DPIC_ARG_INT mip,                   \
    DPIC_ARG_INT mie,                   \
    DPIC_ARG_INT mscratch,              \
    DPIC_ARG_INT sscratch,              \
    DPIC_ARG_INT mideleg,               \
    DPIC_ARG_INT medeleg                \
  ) */
#define INTERFACE_CSR_STATE           \
  DIFFTEST_DPIC_FUNC_DECL(CSRState) ( \
    DPIC_ARG_BYTE coreid,               \
    DPIC_ARG_INT crmd,                 \
    DPIC_ARG_INT prmd,                 \
    DPIC_ARG_INT euen,                 \
    DPIC_ARG_INT ecfg,                 \
    DPIC_ARG_INT estat,                \
    DPIC_ARG_INT era,                  \
    DPIC_ARG_INT badv,                 \
    DPIC_ARG_INT eentry,               \
    DPIC_ARG_INT tlbidx,               \
    DPIC_ARG_INT tlbehi,               \
    DPIC_ARG_INT tlbelo0,              \
    DPIC_ARG_INT tlbelo1,              \
    DPIC_ARG_INT asid,                 \
    DPIC_ARG_INT pgdl,                 \
    DPIC_ARG_INT pgdh,                 \
    DPIC_ARG_INT save0,                \
    DPIC_ARG_INT save1,                \
    DPIC_ARG_INT save2,                \
    DPIC_ARG_INT save3,                \
    DPIC_ARG_INT tid,                  \
    DPIC_ARG_INT tcfg,                 \
    DPIC_ARG_INT tval,                 \
    DPIC_ARG_INT ticlr,                \
    DPIC_ARG_INT llbctl,               \
    DPIC_ARG_INT tlbrentry,            \
    DPIC_ARG_INT dmw0,                 \
    DPIC_ARG_INT dmw1                  \
  )

// v_difftest_ArchIntRegState
#define INTERFACE_INT_REG_STATE          \
  DIFFTEST_DPIC_FUNC_DECL(ArchIntRegState) ( \
    DPIC_ARG_BYTE coreid,                \
    DPIC_ARG_INT gpr_0,                 \
    DPIC_ARG_INT gpr_1,                 \
    DPIC_ARG_INT gpr_2,                 \
    DPIC_ARG_INT gpr_3,                 \
    DPIC_ARG_INT gpr_4,                 \
    DPIC_ARG_INT gpr_5,                 \
    DPIC_ARG_INT gpr_6,                 \
    DPIC_ARG_INT gpr_7,                 \
    DPIC_ARG_INT gpr_8,                 \
    DPIC_ARG_INT gpr_9,                 \
    DPIC_ARG_INT gpr_10,                \
    DPIC_ARG_INT gpr_11,                \
    DPIC_ARG_INT gpr_12,                \
    DPIC_ARG_INT gpr_13,                \
    DPIC_ARG_INT gpr_14,                \
    DPIC_ARG_INT gpr_15,                \
    DPIC_ARG_INT gpr_16,                \
    DPIC_ARG_INT gpr_17,                \
    DPIC_ARG_INT gpr_18,                \
    DPIC_ARG_INT gpr_19,                \
    DPIC_ARG_INT gpr_20,                \
    DPIC_ARG_INT gpr_21,                \
    DPIC_ARG_INT gpr_22,                \
    DPIC_ARG_INT gpr_23,                \
    DPIC_ARG_INT gpr_24,                \
    DPIC_ARG_INT gpr_25,                \
    DPIC_ARG_INT gpr_26,                \
    DPIC_ARG_INT gpr_27,                \
    DPIC_ARG_INT gpr_28,                \
    DPIC_ARG_INT gpr_29,                \
    DPIC_ARG_INT gpr_30,                \
    DPIC_ARG_INT gpr_31                 \
  )

// v_difftest_ArchFpRegState
#define INTERFACE_FP_REG_STATE           \
  DIFFTEST_DPIC_FUNC_DECL(ArchFpRegState) ( \
    DPIC_ARG_BYTE coreid,                \
    DPIC_ARG_INT fpr_0,                 \
    DPIC_ARG_INT fpr_1,                 \
    DPIC_ARG_INT fpr_2,                 \
    DPIC_ARG_INT fpr_3,                 \
    DPIC_ARG_INT fpr_4,                 \
    DPIC_ARG_INT fpr_5,                 \
    DPIC_ARG_INT fpr_6,                 \
    DPIC_ARG_INT fpr_7,                 \
    DPIC_ARG_INT fpr_8,                 \
    DPIC_ARG_INT fpr_9,                 \
    DPIC_ARG_INT fpr_10,                \
    DPIC_ARG_INT fpr_11,                \
    DPIC_ARG_INT fpr_12,                \
    DPIC_ARG_INT fpr_13,                \
    DPIC_ARG_INT fpr_14,                \
    DPIC_ARG_INT fpr_15,                \
    DPIC_ARG_INT fpr_16,                \
    DPIC_ARG_INT fpr_17,                \
    DPIC_ARG_INT fpr_18,                \
    DPIC_ARG_INT fpr_19,                \
    DPIC_ARG_INT fpr_20,                \
    DPIC_ARG_INT fpr_21,                \
    DPIC_ARG_INT fpr_22,                \
    DPIC_ARG_INT fpr_23,                \
    DPIC_ARG_INT fpr_24,                \
    DPIC_ARG_INT fpr_25,                \
    DPIC_ARG_INT fpr_26,                \
    DPIC_ARG_INT fpr_27,                \
    DPIC_ARG_INT fpr_28,                \
    DPIC_ARG_INT fpr_29,                \
    DPIC_ARG_INT fpr_30,                \
    DPIC_ARG_INT fpr_31                 \
  )

// v_difftest_SbufferEvent
/* #define INTERFACE_SBUFFER_EVENT          \
  DIFFTEST_DPIC_FUNC_DECL(SbufferEvent) ( \
    DPIC_ARG_BYTE coreid,                \
    DPIC_ARG_BIT  sbufferResp,           \
    DPIC_ARG_INT sbufferAddr,           \
    DPIC_ARG_BYTE sbufferData_0,         \
    DPIC_ARG_BYTE sbufferData_1,         \
    DPIC_ARG_BYTE sbufferData_2,         \
    DPIC_ARG_BYTE sbufferData_3,         \
    DPIC_ARG_BYTE sbufferData_4,         \
    DPIC_ARG_BYTE sbufferData_5,         \
    DPIC_ARG_BYTE sbufferData_6,         \
    DPIC_ARG_BYTE sbufferData_7,         \
    DPIC_ARG_BYTE sbufferData_8,         \
    DPIC_ARG_BYTE sbufferData_9,         \
    DPIC_ARG_BYTE sbufferData_10,        \
    DPIC_ARG_BYTE sbufferData_11,        \
    DPIC_ARG_BYTE sbufferData_12,        \
    DPIC_ARG_BYTE sbufferData_13,        \
    DPIC_ARG_BYTE sbufferData_14,        \
    DPIC_ARG_BYTE sbufferData_15,        \
    DPIC_ARG_BYTE sbufferData_16,        \
    DPIC_ARG_BYTE sbufferData_17,        \
    DPIC_ARG_BYTE sbufferData_18,        \
    DPIC_ARG_BYTE sbufferData_19,        \
    DPIC_ARG_BYTE sbufferData_20,        \
    DPIC_ARG_BYTE sbufferData_21,        \
    DPIC_ARG_BYTE sbufferData_22,        \
    DPIC_ARG_BYTE sbufferData_23,        \
    DPIC_ARG_BYTE sbufferData_24,        \
    DPIC_ARG_BYTE sbufferData_25,        \
    DPIC_ARG_BYTE sbufferData_26,        \
    DPIC_ARG_BYTE sbufferData_27,        \
    DPIC_ARG_BYTE sbufferData_28,        \
    DPIC_ARG_BYTE sbufferData_29,        \
    DPIC_ARG_BYTE sbufferData_30,        \
    DPIC_ARG_BYTE sbufferData_31,        \
    DPIC_ARG_BYTE sbufferData_32,        \
    DPIC_ARG_BYTE sbufferData_33,        \
    DPIC_ARG_BYTE sbufferData_34,        \
    DPIC_ARG_BYTE sbufferData_35,        \
    DPIC_ARG_BYTE sbufferData_36,        \
    DPIC_ARG_BYTE sbufferData_37,        \
    DPIC_ARG_BYTE sbufferData_38,        \
    DPIC_ARG_BYTE sbufferData_39,        \
    DPIC_ARG_BYTE sbufferData_40,        \
    DPIC_ARG_BYTE sbufferData_41,        \
    DPIC_ARG_BYTE sbufferData_42,        \
    DPIC_ARG_BYTE sbufferData_43,        \
    DPIC_ARG_BYTE sbufferData_44,        \
    DPIC_ARG_BYTE sbufferData_45,        \
    DPIC_ARG_BYTE sbufferData_46,        \
    DPIC_ARG_BYTE sbufferData_47,        \
    DPIC_ARG_BYTE sbufferData_48,        \
    DPIC_ARG_BYTE sbufferData_49,        \
    DPIC_ARG_BYTE sbufferData_50,        \
    DPIC_ARG_BYTE sbufferData_51,        \
    DPIC_ARG_BYTE sbufferData_52,        \
    DPIC_ARG_BYTE sbufferData_53,        \
    DPIC_ARG_BYTE sbufferData_54,        \
    DPIC_ARG_BYTE sbufferData_55,        \
    DPIC_ARG_BYTE sbufferData_56,        \
    DPIC_ARG_BYTE sbufferData_57,        \
    DPIC_ARG_BYTE sbufferData_58,        \
    DPIC_ARG_BYTE sbufferData_59,        \
    DPIC_ARG_BYTE sbufferData_60,        \
    DPIC_ARG_BYTE sbufferData_61,        \
    DPIC_ARG_BYTE sbufferData_62,        \
    DPIC_ARG_BYTE sbufferData_63,        \
    DPIC_ARG_INT sbufferMask            \
  ) */

// v_difftest_StoreEvent
#define INTERFACE_STORE_EVENT            \
  DIFFTEST_DPIC_FUNC_DECL(StoreEvent) (  \
    DPIC_ARG_BYTE coreid,                \
    DPIC_ARG_BYTE index,                 \
    DPIC_ARG_BIT  valid,                 \
    DPIC_ARG_INT storeAddr,             \
    DPIC_ARG_INT storeData,             \
    DPIC_ARG_BYTE storeMask              \
  )

// v_difftest_LoadEvent
#define INTERFACE_LOAD_EVENT             \
  DIFFTEST_DPIC_FUNC_DECL(LoadEvent) (   \
    DPIC_ARG_BYTE coreid,                \
    DPIC_ARG_BYTE index,                 \
    DPIC_ARG_BIT  valid,                 \
    DPIC_ARG_INT paddr,                 \
    DPIC_ARG_BYTE opType,                \
    DPIC_ARG_BYTE fuType                 \
  )

// v_difftest_AtomicEvent
/* #define INTERFACE_ATOMIC_EVENT           \
  DIFFTEST_DPIC_FUNC_DECL(AtomicEvent) ( \
    DPIC_ARG_BYTE coreid,                \
    DPIC_ARG_BIT  resp,            \
    DPIC_ARG_INT addr,            \
    DPIC_ARG_INT data,            \
    DPIC_ARG_BYTE mask,            \
    DPIC_ARG_BYTE fuop,            \
    DPIC_ARG_INT out              \
  ) */

// v_difftest_PtwEvent
/* #define INTERFACE_PTW_EVENT              \
  DIFFTEST_DPIC_FUNC_DECL(PtwEvent) (    \
    DPIC_ARG_BYTE coreid,                \
    DPIC_ARG_BIT  resp,                  \
    DPIC_ARG_INT addr,                  \
    DPIC_ARG_INT data_0,                \
    DPIC_ARG_INT data_1,                \
    DPIC_ARG_INT data_2,                \
    DPIC_ARG_INT data_3                 \
  ) */

// INTERFACE_ARCH_EVENT;
INTERFACE_INSTR_COMMIT;
INTERFACE_TRAP_EVENT;
INTERFACE_CSR_STATE;
INTERFACE_INT_REG_STATE;
INTERFACE_FP_REG_STATE;
// INTERFACE_SBUFFER_EVENT;
INTERFACE_STORE_EVENT;
INTERFACE_LOAD_EVENT;
// INTERFACE_ATOMIC_EVENT;
// INTERFACE_PTW_EVENT;

#endif