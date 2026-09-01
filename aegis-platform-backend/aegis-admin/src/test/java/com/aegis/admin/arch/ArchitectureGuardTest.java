package com.aegis.admin.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构守护测试：强制 admin 模块分层约束。
 *
 * <p>通过 ArchUnit 强制实施分层架构规则，防止层次穿透、依赖反转等架构腐化。
 */
@DisplayName("架构守护测试 - admin 模块分层约束")
public class ArchitectureGuardTest {

    private static JavaClasses adminClasses;
    private static JavaClasses allProjectClasses;

    @BeforeAll
    static void init() {
        adminClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aegis.admin");

        allProjectClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aegis");
    }

    /**
     * R1: web 层不得直接依赖 infrastructure 包（必须通过 service 层）。
     */
    @Test
    @DisplayName("R1: web 层不得直接依赖 infrastructure")
    void web_layer_must_not_access_infrastructure_directly() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..admin.web..")
                .should().dependOnClassesThat()
                .resideInAPackage("..admin.infrastructure..");

        rule.check(adminClasses);
    }

    /**
     * R2: dal 的 mapper 子包不应包含 @Service（业务逻辑归 service 层）。
     */
    @Test
    @DisplayName("R2: dal-mapper 不含业务 @Service")
    void dal_layer_must_not_contain_service_annotations() {
        long count = allProjectClasses.stream()
                .filter(c -> c.getPackageName().startsWith("com.aegis.dal.mapper"))
                .filter(c -> c.isAnnotatedWith(org.springframework.stereotype.Service.class))
                .count();
        Assertions.assertEquals(0, count,
                "dal.mapper 不应包含 @Service 注解类");
    }

    /**
     * R3: core-domain 不依赖 Spring 框架（纯 POJO）。
     */
    @Test
    @DisplayName("R3: core-domain 不含 spring 依赖")
    void core_domain_must_not_depend_on_spring() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..core.domain..")
                .should().dependOnClassesThat()
                .resideInAPackage("org.springframework..");

        rule.check(allProjectClasses);
    }

    /**
     * R4: Controller 命名规范：必须以 Controller 结尾且位于 web 包。
     */
    @Test
    @DisplayName("R4: Controller 命名规范")
    void controllers_must_follow_naming_convention() {
        ArchRule rule = classes()
                .that().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
                .should().haveSimpleNameEndingWith("Controller")
                .andShould().resideInAPackage("..admin.web..");

        rule.allowEmptyShould(true).check(adminClasses);
    }

    /**
     * R5: @Service 必须位于 service 子包或 infrastructure 子包。
     */
    @Test
    @DisplayName("R5: @Service 位置约束")
    void services_must_reside_in_correct_package() {
        ArchRule rule = classes()
                .that().areAnnotatedWith(org.springframework.stereotype.Service.class)
                .should().resideInAPackage("..admin.service..")
                .orShould().resideInAPackage("..admin.infrastructure..")
                .orShould().resideInAPackage("..admin.config..");

        rule.allowEmptyShould(true).check(adminClasses);
    }

    /**
     * R6: Mapper 接口必须位于 dal.mapper 包。
     */
    @Test
    @DisplayName("R6: Mapper 必须在 dal.mapper 包")
    void mappers_must_reside_in_dal_mapper() {
        ArchRule rule = classes()
                .that().areAnnotatedWith(org.apache.ibatis.annotations.Mapper.class)
                .should().resideInAPackage("..dal.mapper..");

        rule.allowEmptyShould(true).check(allProjectClasses);
    }

    /**
     * R7: core-dto 不应依赖 MyBatis 注解。
     * 仅允许 core-domain 中的实体使用 ORM 注解。
     */
    @Test
    @DisplayName("R7: core-dto 不含 mybatis 注解")
    void core_dto_must_not_have_mybatis_annotations() {
        long count = allProjectClasses.stream()
                .filter(c -> c.getPackageName().startsWith("com.aegis.core.dto"))
                .filter(c -> {
                    boolean hasTable = c.isAnnotatedWith(com.baomidou.mybatisplus.annotation.TableField.class)
                            || c.isAnnotatedWith(com.baomidou.mybatisplus.annotation.TableId.class)
                            || c.isAnnotatedWith(com.baomidou.mybatisplus.annotation.TableName.class);
                    return hasTable;
                })
                .count();
        Assertions.assertEquals(0, count,
                "core-dto 不应包含 MyBatis-Plus 注解");
    }

    /**
     * R8: core-domain 不得依赖 MyBatis-Plus（ORM 注解）—— 已知技术债，暂不强制。
     *
     * <p>core-domain 必须保持纯领域模型，零 ORM 框架依赖。当前因 54 个领域实体 + 2 个基类
     * （BaseEntity/TenantEntity）仍携带 @TableName/@TableField/@TableId/@TableLogic 注解，
     * 规则以 @Disabled 标记。
     *
     * <p><b>已知技术债</b>：domain 纯实体 vs dal PO 拆分未排期，本规则暂不强制。
     * 后续若启动 PO 迁移专项，移除 @Disabled 即强制生效，届时 aegis-core-domain 也将从 pom
     * 移除 mybatis-plus-annotation 依赖。当前保留 @Disabled 仅为声明该约束存在，非"待启用"。
     */
    @Test
    @DisplayName("R8: core-domain 不依赖 MyBatis-Plus（待 P1 PO 迁移后启用）")
    @org.junit.jupiter.api.Disabled
    void core_domain_must_not_depend_on_mybatis_plus() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..core.domain..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.baomidou..");

        rule.check(allProjectClasses);
    }

    /**
     * R9: Controller 不得直接依赖 Mapper（必须通过 Service 层）。
     *
     * <p>防止"架构破窗效应"——Controller 直连 Mapper 绕过 Service，导致业务逻辑散落 Web 层、
     * 事务边界缺失、领域规则不可复用。P1 已将 5 处违规下沉至 Service，此规则即刻生效守护。
     *
     * <p>参见《后端代码缺陷与优化方案》1.1 节。
     */
    @Test
    @DisplayName("R9: Controller 不直接依赖 Mapper")
    void controllers_must_not_depend_on_mapper() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..admin.web..")
                .should().dependOnClassesThat()
                .resideInAPackage("..dal.mapper..");

        rule.check(adminClasses);
    }
}
