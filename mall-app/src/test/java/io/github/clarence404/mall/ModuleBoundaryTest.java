package io.github.clarence404.mall;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;

@AnalyzeClasses(packages = "io.github.clarence404.mall", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    /** 业务模块：io.github.clarence404.mall.&lt;模块&gt;..，对外只暴露 io.github.clarence404.mall.&lt;模块&gt;.api.. */
    static final List<String> BUSINESS_MODULES = List.of("user", "product", "inventory", "order", "payment");

    // 每个业务模块只能使用其他业务模块的 api 包（订单、商品、库存、用户、支付一视同仁）
    @ArchTest
    static final ArchRule business_modules_only_use_api_of_other_modules = classes()
            .that()
            .resideInAnyPackage(BUSINESS_MODULES.stream()
                    .map(m -> "io.github.clarence404.mall." + m + "..")
                    .toArray(String[]::new))
            .should(onlyUseApiOfOtherModules())
            .allowEmptyShould(true);

    // 业务模块之间不允许循环依赖
    @ArchTest
    static final ArchRule modules_free_of_cycles =
            slices().matching("io.github.clarence404.mall.(*)..").should().beFreeOfCycles();

    private static ArchCondition<JavaClass> onlyUseApiOfOtherModules() {
        return new ArchCondition<>("only use the api package of other business modules") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String own = moduleOf(origin.getPackageName());
                for (Dependency d : origin.getDirectDependenciesFromSelf()) {
                    String target = d.getTargetClass().getBaseComponentType().getPackageName();
                    String other = moduleOf(target);
                    if (other == null || other.equals(own)) {
                        continue;
                    }
                    String api = "io.github.clarence404.mall." + other + ".api";
                    if (!(target.equals(api) || target.startsWith(api + "."))) {
                        events.add(SimpleConditionEvent.violated(d, d.getDescription()));
                    }
                }
            }
        };
    }

    /** 包名所属的业务模块；不属于业务模块（如 common、启动类所在的根包）返回 null。 */
    static String moduleOf(String packageName) {
        for (String m : BUSINESS_MODULES) {
            String root = "io.github.clarence404.mall." + m;
            if (packageName.equals(root) || packageName.startsWith(root + ".")) {
                return m;
            }
        }
        return null;
    }
}
