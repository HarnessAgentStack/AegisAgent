package com.aegis.runtime.integration.skill;

import com.aegis.core.domain.resource.Skill;
import com.aegis.core.spi.IObjectStorage;
import com.aegis.dal.mapper.resource.SkillMapper;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 技能打包工具：SKILL.md 生成、压缩包导出、对象存储上传。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillPackagerTool {

    private final SkillMapper skillMapper;
    private final IObjectStorage objectStorage;

    /**
     * 生成 SKILL.md 内容。
     */
    public String generateSkillMd(Long skillId) {
        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            return "# 技能不存在\n\n技能 ID: " + skillId;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(skill.getSkillName()).append("\n\n");
        sb.append("## 元数据\n\n");
        sb.append("| 字段 | 值 |\n|------|----|\n");
        sb.append("| 技能编码 | `").append(skill.getSkillCode()).append("` |\n");
        sb.append("| 版本 | ").append(skill.getVersion() != null ? skill.getVersion() : "0.1.0").append(" |\n");
        sb.append("| 类型 | ").append(skill.getSkillType() != null ? skill.getSkillType().name() : "COMPOSITE").append(" |\n");
        sb.append("| 分类 | ").append(skill.getCategory() != null ? skill.getCategory().getDesc() : "未分类").append(" |\n");
        sb.append("| 作用域 | ").append(skill.getScope() != null ? skill.getScope().getDesc() : "局部").append(" |\n");
        sb.append("| 安全等级 | ").append(skill.getSecurityLevel() != null ? skill.getSecurityLevel().getDesc() : "L2").append(" |\n\n");
        
        if (skill.getDescription() != null) {
            sb.append("## 描述\n\n").append(skill.getDescription()).append("\n\n");
        }
        
        if (skill.getInstructions() != null) {
            sb.append("## 方法论\n\n").append(skill.getInstructions()).append("\n\n");
        }
        
        if (skill.getInputs() != null) {
            sb.append("## 输入参数\n\n```json\n").append(skill.getInputs()).append("\n```\n\n");
        }
        
        if (skill.getOutputs() != null) {
            sb.append("## 输出参数\n\n```json\n").append(skill.getOutputs()).append("\n```\n\n");
        }
        
        if (skill.getBindingTools() != null) {
            sb.append("## 绑定工具\n\n```json\n").append(skill.getBindingTools()).append("\n```\n\n");
        }
        
        sb.append("## 使用说明\n\n");
        sb.append("1. 将此文件放入你的项目或技能仓库\n");
        sb.append("2. 通过 Aegis 平台或 AgentScope 加载此技能\n");
        sb.append("3. 在对话中通过 @").append(skill.getSkillCode()).append(" 调用\n");
        
        return sb.toString();
    }

    /**
     * 打包技能为 ZIP 压缩包（仅生成本地数据，不上传对象存储）。
     */
    public PackageResult packageSkill(Long skillId) {
        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            return PackageResult.fail("技能不存在: " + skillId);
        }

        String skillMd = generateSkillMd(skillId);
        String version = skill.getVersion() != null ? skill.getVersion() : "0.1.0";
        String packageName = "skill_" + skill.getSkillCode() + "_v" + version;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {

            zos.putNextEntry(new ZipEntry(packageName + "/SKILL.md"));
            zos.write(skillMd.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry(packageName + "/skill.json"));
            Map<String, Object> meta = new HashMap<>();
            meta.put("skill_code", skill.getSkillCode());
            meta.put("skill_name", skill.getSkillName());
            meta.put("version", version);
            meta.put("type", skill.getSkillType() != null ? skill.getSkillType().name() : "COMPOSITE");
            meta.put("scope", skill.getScope() != null ? skill.getScope().name() : "LOCAL");
            meta.put("description", skill.getDescription());
            zos.write(JSON.toJSONString(meta).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry(packageName + "/README.md"));
            String readme = buildReadme(skill, version);
            zos.write(readme.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.finish();

            byte[] zipBytes = baos.toByteArray();
            log.info("技能打包完成: skillId={}, packageSize={}bytes", skillId, zipBytes.length);

            return PackageResult.success(packageName + ".zip", zipBytes);
        } catch (IOException e) {
            log.error("技能打包失败: skillId={}", skillId, e);
            return PackageResult.fail("打包失败: " + e.getMessage());
        }
    }

    /**
     * 打包技能并上传到对象存储，返回下载 URL。
     *
     * @param skillId  技能ID
     * @param tenantId 租户ID（用于对象存储隔离）
     * @return 包含下载 URL 的打包结果
     */
    public PackageResult packageAndUpload(Long skillId, Long tenantId) {
        PackageResult localResult = packageSkill(skillId);
        if (!localResult.isSuccess()) {
            return localResult;
        }

        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            return PackageResult.fail("技能不存在: " + skillId);
        }

        try {
            String version = skill.getVersion() != null ? skill.getVersion() : "0.1.0";
            String objectKey = "skills/" + skill.getSkillCode() + "/skill_" + skill.getSkillCode() + "_v" + version + ".zip";

            String storedKey = objectStorage.upload(
                    tenantId,
                    objectKey,
                    new ByteArrayInputStream(localResult.getData()),
                    "application/zip"
            );

            String downloadUrl = objectStorage.presignedDownloadUrl(
                    tenantId,
                    objectKey,
                    7, TimeUnit.DAYS
            );

            log.info("技能包上传成功: skillId={}, storedKey={}, downloadUrl={}", skillId, storedKey, downloadUrl);

            localResult.setPackageUrl(downloadUrl);
            localResult.setStoredKey(storedKey);
            localResult.setMessage("打包并上传成功");

            return localResult;
        } catch (Exception e) {
            log.error("技能包上传对象存储失败: skillId={}", skillId, e);
            localResult.setMessage("打包成功但上传失败: " + e.getMessage());
            return localResult;
        }
    }

    /**
     * 从对象存储下载技能包。
     *
     * @param skillId  技能ID
     * @param tenantId 租户ID
     * @return 下载的技能包数据
     */
    public PackageResult downloadFromStorage(Long skillId, Long tenantId) {
        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            return PackageResult.fail("技能不存在: " + skillId);
        }

        try {
            String version = skill.getVersion() != null ? skill.getVersion() : "0.1.0";
            String objectKey = "skills/" + skill.getSkillCode() + "/skill_" + skill.getSkillCode() + "_v" + version + ".zip";

            try (var inputStream = objectStorage.download(tenantId, objectKey);
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    baos.write(buffer, 0, bytesRead);
                }

                String fileName = "skill_" + skill.getSkillCode() + "_v" + version + ".zip";
                log.info("从对象存储下载技能包: skillId={}, size={}bytes", skillId, baos.size());

                return PackageResult.success(fileName, baos.toByteArray());
            }
        } catch (Exception e) {
            log.error("从对象存储下载技能包失败: skillId={}", skillId, e);
            return PackageResult.fail("下载失败: " + e.getMessage());
        }
    }

    private String buildReadme(Skill skill, String version) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(skill.getSkillName()).append("\n\n");
        sb.append("## 安装说明\n\n");
        sb.append("### 方式一：通过 Aegis 平台导入\n");
        sb.append("1. 登录 Aegis 平台\n");
        sb.append("2. 进入「技能中心」\n");
        sb.append("3. 点击「导入技能」，上传本压缩包\n\n");
        sb.append("### 方式二：通过 AgentScope 加载\n");
        sb.append("```java\n");
        sb.append("AgentSkill skill = skillRepository.load(\"").append(skill.getSkillCode()).append("\");\n");
        sb.append("```\n\n");
        sb.append("## 版本：").append(version).append("\n");
        return sb.toString();
    }

    @lombok.Data
    public static class PackageResult {
        private boolean success;
        private String message;
        private String fileName;
        private byte[] data;
        /** 对象存储下载 URL（预签名，有效期 7 天） */
        private String packageUrl;
        /** 对象存储存储的完整键 */
        private String storedKey;

        public static PackageResult success(String fileName, byte[] data) {
            PackageResult r = new PackageResult();
            r.setSuccess(true);
            r.setFileName(fileName);
            r.setData(data);
            r.setMessage("打包成功");
            return r;
        }

        public static PackageResult fail(String msg) {
            PackageResult r = new PackageResult();
            r.setSuccess(false);
            r.setMessage(msg);
            return r;
        }
    }
}