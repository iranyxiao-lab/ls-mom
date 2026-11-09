package com.lsmom.architecture;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/**
 * 模块依赖方向（design.md D4，spec platform/project-baseline「模块依赖方向受控」）。
 */
class ModuleDependencyTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.lsmom");
    }

    /** core 是最底层：不依赖任何其他 LS-MOM 模块，更不依赖 Pack。 */
    private static final ArchRule coreDependsOnNothingAbove = noClasses()
        .that().resideInAPackage("com.lsmom.core..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "com.lsmom.meta..", "com.lsmom.action..", "com.lsmom.crud..", "com.lsmom.form..",
            "com.lsmom.flow..", "com.lsmom.pack..", "com.lsmom.ai..", "com.lsmom.mcp..",
            "com.lsmom.dcs..", "com.lsmom.biz..")
        .because("core 不得依赖上层模块或任何 Pack");

    /** 通用运行时（crud/form/flow）不依赖任何具体业务 Pack。 */
    private static final ArchRule runtimeDoesNotDependOnBiz = noClasses()
        .that().resideInAnyPackage("com.lsmom.crud..", "com.lsmom.form..", "com.lsmom.flow..")
        .should().dependOnClassesThat().resideInAPackage("com.lsmom.biz..")
        .because("crud/form/flow 不得依赖具体客户业务");

    /** 业务 Pack 只能使用 core/action/flow/meta 的公开能力。 */
    private static final ArchRule bizOnlyUsesAllowedModules = noClasses()
        .that().resideInAPackage("com.lsmom.biz..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "com.lsmom.crud..", "com.lsmom.form..", "com.lsmom.pack..",
            "com.lsmom.ai..", "com.lsmom.mcp..", "com.lsmom.dcs..")
        .because("biz 只依赖 core/action/flow/meta");

    /** Pack 之间只能通过 Concept、Operation、Event 交互，不能直接引用对方的类。 */
    private static final ArchRule packsAreIndependent = SlicesRuleDefinition.slices()
        .matching("com.lsmom.biz.(*)..")
        .should().notDependOnEachOther()
        .because("Pack 之间不得直接引用对方实现");

    /** ai 与 mcp 只能经 Operation 访问业务，禁止触达数据访问层。 */
    private static final ArchRule aiAndMcpDoNotTouchMappers = noClasses()
        .that().resideInAnyPackage("com.lsmom.ai..", "com.lsmom.mcp..")
        .should().dependOnClassesThat().resideInAPackage("..mapper..")
        .orShould().dependOnClassesThat().areAssignableTo(BaseMapper.class)
        .orShould().dependOnClassesThat().areAnnotatedWith(Mapper.class)
        .because("ai/mcp 只能调用 Operation，不能直接访问 Mapper");

    /** ai 与 mcp 在 LS-MOM 内部只依赖 action（及 action 暴露的类型）。 */
    private static final ArchRule aiAndMcpOnlyUseAction = noClasses()
        .that().resideInAnyPackage("com.lsmom.ai..", "com.lsmom.mcp..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "com.lsmom.crud..", "com.lsmom.form..", "com.lsmom.flow..",
            "com.lsmom.pack..", "com.lsmom.dcs..", "com.lsmom.biz..")
        .because("ai/mcp → action，禁止绕过 Operation");

    @Test
    void coreDependsOnNothingAbove() {
        coreDependsOnNothingAbove.check(classes);
    }

    @Test
    void runtimeDoesNotDependOnBiz() {
        runtimeDoesNotDependOnBiz.check(classes);
    }

    @Test
    void bizOnlyUsesAllowedModules() {
        bizOnlyUsesAllowedModules.check(classes);
    }

    @Test
    void packsAreIndependent() {
        packsAreIndependent.check(classes);
    }

    @Test
    void aiAndMcpDoNotTouchMappers() {
        aiAndMcpDoNotTouchMappers.check(classes);
    }

    @Test
    void aiAndMcpOnlyUseAction() {
        aiAndMcpOnlyUseAction.check(classes);
    }

    /** 核心语义不含客户/扩展领域概念（spec meta/concept-registry「核心污染检测」）。 */
    static final String CORE_FORBIDDEN = ".*(?i)(exam|training|trainee|course|lesson|quiz|consult|survey|customer).*";

    @Test
    void coreHasNoExtensionDomainConcepts() {
        noClasses().that().resideInAPackage("com.lsmom.core..").should().haveNameMatching(CORE_FORBIDDEN)
            .because("ls.core 只承载通用语义，培训、咨询等进对应 Pack").check(classes);
        noFields().that().areDeclaredInClassesThat().resideInAPackage("com.lsmom.core..").should().haveNameMatching(CORE_FORBIDDEN)
            .because("ls.core 只承载通用语义，培训、咨询等进对应 Pack").check(classes);
    }
}
