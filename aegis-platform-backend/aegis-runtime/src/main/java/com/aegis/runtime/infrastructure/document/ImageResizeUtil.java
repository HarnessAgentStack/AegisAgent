package com.aegis.runtime.infrastructure.document;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 图片缩放工具类。
 *
 * <p>为多模态 LLM 发送图片做预处理：当图片分辨率或文件大小超出模型限制时，
 * 按比例等比缩放，确保图片能顺利通过 API 传输。所有异常均降级返回原图字节。</p>
 *
 * <h3>缩放策略</h3>
 * <ol>
 *   <li>先用 {@link ImageIO} 读取图片宽高（JDK 自带，无需额外依赖）</li>
 *   <li>如果宽或高超过 {@code maxEdge}，按比例缩放到 {@code maxEdge}</li>
 *   <li>如果缩放后文件大小仍超过 {@code maxBytes}，继续等比缩小，直到满足或不可再缩</li>
 *   <li>输出为 JPEG 格式（跨平台通用）；原文件为 PNG/WEBP 等带透明通道格式时
 *       会被转为 JPEG（透明通道填白），这是模型侧通常可接受的取舍</li>
 * </ol>
 *
 * @author wang.zhen
 */
@Slf4j
public class ImageResizeUtil {

    /** 默认最大边（像素），匹配常见多模态模型限制（如 GPT-4o / 豆包视觉） */
    private static final int DEFAULT_MAX_EDGE = 2048;

    /** 默认最大文件大小（字节），匹配常见模型限制 20MB */
    private static final int DEFAULT_MAX_BYTES = 20 * 1024 * 1024;

    /** 内部缩放迭代最大次数（避免极端图片死循环） */
    private static final int MAX_ITERATIONS = 5;

    /** 私有构造器，禁止实例化 */
    private ImageResizeUtil() {
    }

    /**
     * 按默认参数（2048px / 20MB）缩放图片。
     *
     * @param imageBytes 原始图片字节
     * @param filename   文件名（用于推断 MIME / 输出格式）
     * @return 缩放后的图片字节；异常时返回原图
     */
    public static byte[] resizeIfNeeded(byte[] imageBytes, String filename) {
        return resizeIfNeeded(imageBytes, filename, DEFAULT_MAX_EDGE, DEFAULT_MAX_BYTES);
    }

    /**
     * 按自定义参数缩放图片。
     *
     * <p>任何异常（IO 错误、解码失败、非图片输入）都会被 catch 并返回原字节，
     * 调用方永不感知降级。
     *
     * @param imageBytes 原始图片字节
     * @param filename   文件名
     * @param maxEdge    最大边（像素），0 表示不限制
     * @param maxBytes   最大文件大小（字节），0 表示不限制
     * @return 缩放后的图片字节；异常时返回原图
     */
    public static byte[] resizeIfNeeded(byte[] imageBytes, String filename,
                                         int maxEdge, int maxBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            return imageBytes;
        }

        // 如果已经小于限制，直接返回
        boolean edgeOk = maxEdge <= 0;
        boolean bytesOk = maxBytes <= 0 || imageBytes.length <= maxBytes;

        BufferedImage img;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes)) {
            img = ImageIO.read(bais);
        } catch (IOException e) {
            log.warn("图片解码失败，跳过缩放: filename={}, error={}", filename, e.getMessage());
            return imageBytes;
        }

        if (img == null) {
            // ImageIO 无法识别该格式（如 webp 无扩展支持），返回原图
            log.warn("无法识别的图片格式，跳过缩放: filename={}", filename);
            return imageBytes;
        }

        int width = img.getWidth();
        int height = img.getHeight();

        // 判断是否需要缩放
        if (!edgeOk && Math.max(width, height) > maxEdge) {
            edgeOk = false;
        }
        if (edgeOk && bytesOk) {
            // 完全满足限制，直接返回原图
            return imageBytes;
        }

        // 执行缩放迭代
        byte[] result = imageBytes;
        int currentW = width;
        int currentH = height;
        BufferedImage currentImg = img;

        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            boolean needResize = false;

            // 先按边缩放
            if (maxEdge > 0 && Math.max(currentW, currentH) > maxEdge) {
                double scale = (double) maxEdge / Math.max(currentW, currentH);
                int newW = Math.max(1, (int) Math.round(currentW * scale));
                int newH = Math.max(1, (int) Math.round(currentH * scale));
                log.debug("图片按边缩放: {}x{} -> {}x{}, iter={}", currentW, currentH, newW, newH, iter);
                currentImg = doScale(currentImg, newW, newH);
                currentW = newW;
                currentH = newH;
                needResize = true;
            }

            // 编码为 JPEG 检查文件大小
            result = encodeToJpeg(currentImg);
            if (result == null) {
                log.warn("图片编码失败，返回原图: filename={}", filename);
                return imageBytes;
            }

            if (maxBytes > 0 && result.length > maxBytes) {
                // 文件大小仍然超限，继续按比例缩小
                double scale = Math.sqrt((double) maxBytes / result.length);
                int newW = Math.max(1, (int) Math.round(currentW * scale));
                int newH = Math.max(1, (int) Math.round(currentH * scale));
                if (newW == currentW && newH == currentH) {
                    // 已缩无可缩（单像素图片），直接返回
                    break;
                }
                log.debug("图片按文件大小缩放: {}x{} -> {}x{}, size={}KB, limit={}KB, iter={}",
                        currentW, currentH, newW, newH, result.length / 1024, maxBytes / 1024, iter);
                currentImg = doScale(currentImg, newW, newH);
                currentW = newW;
                currentH = newH;
                needResize = true;
                continue; // 继续下一轮检查文件大小
            }

            if (!needResize) {
                // 既不需要按边缩也不需要按大小缩，完成
                break;
            }
        }

        return result;
    }

    /**
     * 判断是否为图片文件（仅靠扩展名）。
     *
     * @param filename 文件名
     * @return true 表示是常见图片格式
     */
    public static boolean isImage(String filename) {
        if (filename == null) {
            return false;
        }
        String lower = filename.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".gif")
                || lower.endsWith(".webp") || lower.endsWith(".bmp");
    }

    /**
     * 根据文件名推断 MIME 类型。
     *
     * @param filename 文件名
     * @return MIME 字符串；无法识别时默认返回 {@code image/png}
     */
    public static String guessMimeType(String filename) {
        if (filename == null) {
            return "image/png";
        }
        String lower = filename.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".bmp")) {
            return "image/bmp";
        }
        return "image/png";
    }

    /**
     * 执行 BufferedImage 的等比缩放（使用双三次插值保证质量）。
     */
    private static BufferedImage doScale(BufferedImage src, int targetW, int targetH) {
        BufferedImage dest = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = dest.createGraphics();
        try {
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            // PNG/WEBP 等带透明通道的图片填白后转为 JPEG
            g2d.setColor(Color.WHITE);
            g2d.fillRect(0, 0, targetW, targetH);
            g2d.drawImage(src, 0, 0, targetW, targetH, null);
            return dest;
        } finally {
            g2d.dispose();
        }
    }

    /**
     * 将 BufferedImage 编码为 JPEG 字节。
     *
     * @return JPEG 字节数组；失败返回 null
     */
    private static byte[] encodeToJpeg(BufferedImage img) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "jpg", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            log.warn("图片 JPEG 编码失败: {}", e.getMessage());
            return null;
        }
    }
}
