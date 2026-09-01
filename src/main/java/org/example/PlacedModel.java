package org.example;

import java.nio.file.Path;

/**
 * 单个模型的放置描述：模型文件 + 贴图 + 世界放置原点（脚底对齐）+ 三轴朝向（任意角度）+ 体素化目标高度（0 表示继承全局 --height）。
 *
 * <p>旋转约定：绕模型自身包围盒中心旋转，三轴角度按「先绕 X、再绕 Y、再绕 Z」的顺序作用于顶点
 * （即组合旋转矩阵 R = Rz(rotZ) · Ry(rotY) · Rx(rotX)）。
 */
record PlacedModel(Path objPath,
                   Path mtlPath,
                   Path texturePath,
                   int placeX,
                   int placeY,
                   int placeZ,
                   int rotationX,
                   int rotationY,
                   int rotationZ,
                   int targetHeight) {

    /** 旧版单轴朝向（绕 Y 轴，仅 0/90/180/270），新代码统一走三轴字段。 */
    static PlacedModel ofLegacyRotation(Path objPath,
                                        Path mtlPath,
                                        Path texturePath,
                                        int placeX,
                                        int placeY,
                                        int placeZ,
                                        int rotationY,
                                        int targetHeight) {
        return new PlacedModel(objPath, mtlPath, texturePath, placeX, placeY, placeZ, 0, rotationY, 0, targetHeight);
    }

    /** 是否需要在体素化时应用几何旋转。 */
    boolean requiresRotation() {
        return rotationX != 0 || rotationY != 0 || rotationZ != 0;
    }
}