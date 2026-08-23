package com.timelapse.camera.watermark

/**
 * 水印配置 —— 控制水印显示哪些信息。
 *
 * 设计为 data class，方便复制修改，且所有布尔字段都有默认值 false，
 * 调用方按需开启即可。
 */
data class WatermarkOptions(
    /** 自定义文字（项目名称等），null 则不显示 */
    val customText: String? = null,
    /** 是否显示电量百分比 */
    val showBattery: Boolean = false,
    /** 是否显示剩余存储空间 */
    val showStorage: Boolean = false,
    /** 是否显示电池温度 */
    val showTemperature: Boolean = false,
    /** 当前电量百分比（0-100），showBattery=true 时有效 */
    val batteryPercent: Int = 0,
    /** 剩余存储空间（GB），showStorage=true 时有效 */
    val storageRemainingGb: Float = 0f,
    /** 电池温度（摄氏度），showTemperature=true 时有效 */
    val temperatureCelsius: Float = 0f
)
