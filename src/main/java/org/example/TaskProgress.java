package org.example;

/**
 * 后台任务进度（Web UI 轮询用）。
 * phase 由各任务自行定义：parse / voxelize / merge / denoise / build / terrain /
 * serialize / compress / write / done；subDone/subTotal 是当前阶段的子进度
 * （解析按字节、体素化按面片、合并/去杂/表面提取按体素、压缩按字节），前端据此显示百分比。
 */
final class TaskProgress {
    volatile String phase = "idle";
    volatile int done;
    volatile int total = 1;
    /** 当前阶段的子进度；total<=0 表示该阶段无法量化（前端显示不定进度条）。 */
    volatile long subDone;
    volatile long subTotal;
    volatile boolean finished;
    volatile String error;
}
