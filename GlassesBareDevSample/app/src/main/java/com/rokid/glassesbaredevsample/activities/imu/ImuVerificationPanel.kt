package com.rokid.glassesbaredevsample.activities.imu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rokid.glassesbaredevsample.sensor.ImuVerificationUiState
import com.rokid.glassesbaredevsample.sensor.SixAxisReading
import com.rokid.glassesbaredevsample.sensor.VerificationPhase
import com.rokid.glassesbaredevsample.ui.design.BareHeroText
import com.rokid.glassesbaredevsample.ui.design.BareInfoBlock
import com.rokid.glassesbaredevsample.ui.imu.ImuGyroMeterStripWithLabels

@Composable
fun ImuVerificationIntro() {
    BareHeroText(
        text = "准备验证",
        hint = "传感器就绪后自动开始",
    )
}

@Composable
fun ImuVerificationRunning(
    state: ImuVerificationUiState,
    readings: SixAxisReading,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        BareHeroText(
            text = "${state.progressLabel} ${state.stepTitle}",
            hint = state.instruction,
        )
        ImuGyroMeterStripWithLabels(
            readings = readings,
            stepIndex = state.stepIndex,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        BareInfoBlock(
            label = state.statusMessage.ifBlank { "等待动作" },
            lines = buildList {
                add("按提示缓慢做动作，停稳约 1 秒")
                add("亮条：当前响应最强的轴")
                if (state.canRetry) {
                    add("长按：重试本步")
                } else {
                    add("双击：取消并返回")
                }
            },
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
fun ImuVerificationComplete(state: ImuVerificationUiState) {
    BareHeroText(text = "验证完成", hint = "单击进入演示")
    BareInfoBlock(
        label = "陀螺仪映射",
        lines = state.result?.summaryLines() ?: emptyList(),
    )
}

@Composable
fun ImuVerificationSection(
    state: ImuVerificationUiState,
    readings: SixAxisReading,
) {
    when (state.phase) {
        VerificationPhase.Intro -> ImuVerificationIntro()
        VerificationPhase.Running -> ImuVerificationRunning(state, readings)
        VerificationPhase.Complete -> ImuVerificationComplete(state)
    }
}
