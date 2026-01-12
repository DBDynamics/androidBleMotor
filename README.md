# androidBleMotor
## 界面设计
- 电机1状态显示 回零传感器状态 当前位置 当前速度 当前运行模式
- 电机1参数设置 加速时间 速度 回零方向 回零电平
- 电机1操作控制 目标位置 回零
- 电机2状态显示 回零传感器状态 当前位置 当前速度 当前运行模式
- 电机2参数设置 加速时间 速度 回零方向 回零电平
- 电机2操作控制 目标位置 回零
- 力传感器数据显示
# 数据结构体
Android端发送指令结构体

#define PDO_CMD_Index_TargerPosition 0
#define PDO_CMD_Index_TargerVelocity 1
#define PDO_CMD_Index_ProfileAccTime 2
#define PDO_CMD_Index_HomingLevel 3
#define PDO_CMD_Index_HomingDir 4
#define PDO_CMD_Index_CurrentBase 5
#define PDO_CMD_Index_CurrentP 6
#define PDO_CMD_Index_CurrentN 7
#define PDO_CMD_Index_FastStopDec 8

#define PDO_CMD_Index_TargerPosition1 6
#define PDO_CMD_Index_TargerVelocity1 7
#define PDO_CMD_Index_ProfileAccTime1 8
#define PDO_CMD_Index_HomingLevel1 9
#define PDO_CMD_Index_HomingDir1 10
#define PDO_CMD_Index_CurrentBase1 11
#define PDO_CMD_Index_CurrentP1 12
#define PDO_CMD_Index_CurrentN1 13
#define PDO_CMD_Index_FastStopDec1 14


#define PDO_ST_Index_StatusWord 0
#define PDO_ST_Index_ActualPosition 1
#define PDO_ST_Index_ActualVelocity 2
#define PDO_ST_Index_StatusWord1 3
#define PDO_ST_Index_ActualPosition1 4
#define PDO_ST_Index_ActualVelocity1 5
#define PDO_ST_Index_Force 15

typedef struct
{
	unsigned char Func;
	unsigned char Index;
	unsigned char ID;
	unsigned char SubID;
	int SDO;
    int PDO[59];
} msgObj;

CMD参数初始化
PDO[PDO_CMD_Index_TargerPosition] = 0;
PDO[PDO_CMD_Index_TargerVelocity] = 1000;
PDO[PDO_CMD_Index_ProfileAccTime] = 200;
PDO[PDO_CMD_Index_HomingLevel] = 0;
PDO[PDO_CMD_Index_HomingDir] = 1;
PDO[PDO_CMD_Index_CurrentBase] = 1200;
PDO[PDO_CMD_Index_CurrentP] = 3;
PDO[PDO_CMD_Index_CurrentN] = 3;
PDO[PDO_CMD_Index_FastStopDec] = 30;

PDO[PDO_CMD_Index_TargerPosition1] = 0;
PDO[PDO_CMD_Index_TargerVelocity1] = 1000;
PDO[PDO_CMD_Index_ProfileAccTime1] = 200;
PDO[PDO_CMD_Index_HomingLevel1] = 0;
PDO[PDO_CMD_Index_HomingDir1] = 1;
PDO[PDO_CMD_Index_CurrentBase1] = 800;
PDO[PDO_CMD_Index_CurrentP1] = 3;
PDO[PDO_CMD_Index_CurrentN1] = 3;
PDO[PDO_CMD_Index_FastStopDec1] = 30;

操作码定义
#define FuncRead 0
#define FuncWrite 1
#define FuncRead_OK 2
#define FuncWrite_OK 3
#define FuncOperation 4
#define FuncOperation_OK 5
#define FuncFree 255

SDO操作逻辑介绍
typedef struct
{
    unsigned char Func;
    unsigned char Index;
    unsigned char ID;
    unsigned char SubID;
    int SDO;
} sdoObj;

创建一个环形队列来存储SDO操作指令
长度100

创建pop函数来从队列中取出指令
若队列为空则返回FreeMsg
具体内容为
Func = 0xff
Index = 0
ID = 0
SubID = 0
SDO = 0
发送蓝牙消息前 从队列中取出指令 并复制到msgObj中


创建一个append函数来向队列中添加指令 入口参数包含
Func
Index
ID
SubID
SDO

创建一个int全局数组 来存储SDO参数 长度64(每个电机 两个电机32)
int valueSDO[64];
/* 电机SDO参数索引 */
//#define BoardTypeIndex 0
#define DeviceIDIndex 1
#define ControlWordIndex 2
#define OperationModeIndex 3
#define StatusWordIndex 4
#define TargetCurrentIndex 5
#define ActualCurrentIndex 6
#define TargetVelocityIndex 7
#define ActualVelocityIndex 8
#define TargetPositionIndex 9
#define ActualPositionIndex 10
#define ProfileAccTimeIndex 11
#define InterpolationTargetPostionIndex 12
#define HomingModeIndex 13
#define HomingDirIndex 14
#define HomingLevelIndex 15
#define HomingOffsetIndex 16 // 为了解决不同设备回零时，触发位置不一致的问题。
#define CurrentBaseIndex 17
#define CurrentPIndex 18
#define CurrentNIndex 19
//#define RuntoKeepTimeIndex 20
//#define BoostTimeIndex 21
#define IoInIndex 22
//#define IoOutIndex 23
// #define EncoderOffsetIndex 24
// #define EncoderPolarityIndex 25
// #define EncoderValueIndex 26
/* for led sync */
#define FastStopDecIndex 28
//#define LedOptionIndex 29
#define SystemCounterIndex 30
//#define LedCounterIndex 31
第二个电机在此基础上+32

创建一个rxMsgAnalyze函数来分析接收的蓝牙消息
入口参数为
msgObj *msg
PDO部分直接更新到对应PDO数组中
SDO部分参考C语言操作逻辑
if(msg->Func == FuncRead_OK)
{
    if(msg->ID == 0x0)
    {
		valueSDO[msg->Index] = msg->SDO;
    }
	else if(msg->ID == 0x1)
	{
		valueSDO[msg->Index+32] = msg->SDO;
	}
}



/* Operation Modes */
#define OPMODE_PWM (0)
#define OPMODE_SVPWM (1)

#define OPMODE_TORQUE (10)
#define OPMODE_SYNC_TORQUE (11)

#define OPMODE_VELOCITY (20)
#define OPMODE_PROFILE_VELOCITY (21)
#define OPMODE_INTERPOLATION_VELOCITY (22)
#define OPMODE_PROFILE_VELOCITY_SYNC (23)
#define OPMODE_INTERPOLATION_VELOCITY_SYNC (24)

#define OPMODE_POSITION (30)
#define OPMODE_PROFILE_POSITION (31)
#define OPMODE_INTERPOLATION_POSITION (32)
#define OPMODE_PROFILE_POSITION_SYNC (33)
#define OPMODE_INTERPOLATION_POSITION_SYNC (34)
#define OPMODE_POSITION_ENCODER (35)
#define OPMODE_SENSOR_FLIP (36)

#define OPMODE_HOMING (40)

#define OPMODE_COS (50)
#define OPMODE_ESTOP_PROFILE (61)
创建一个函数 
setHomingMode(int id)
{
	append(FuncWrite, OperationModeIndex, id, 0x0, OPMODE_HOMING);
	需要同时把CMD PDO 的 TargetPosition设置为0 界面也要刷新
}

在界面上 Motor1 和 Motor2 分别创建一个按钮 Home 用于设置电机回零模式 调用setHomingMode 入口参数分别为0和1

