package archfixtures.constructors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/**
 * Bytecode fixtures for {@code InjectableConstructorTest}: beans Spring could and could not
 * construct. Never instantiated by Spring - this package is deliberately outside {@code
 * com.agenticai.core}, the component-scan root of every application and test context, so the broken
 * ones here cannot break a context. Only read as bytecode.
 */
@SuppressWarnings("unused")
public final class ConstructorFixtures {

    private ConstructorFixtures() {}

    // ---- reported by SPRING_CAN_CHOOSE_A_CONSTRUCTOR --------------------------------------

    /**
     * The HostedToolCallGradeProbe shape: a Spring constructor plus a test seam, neither marked.
     */
    @Component
    public static class TwoConstructorsNoAutowired {
        public TwoConstructorsNoAutowired(String collaborator) {
            this(collaborator, 1);
        }

        TwoConstructorsNoAutowired(String collaborator, int seam) {}
    }

    /** Private constructors count: Spring sees every declared constructor. */
    @Component
    public static class PublicAndPrivateConstructor {
        public PublicAndPrivateConstructor(String collaborator) {
            this(collaborator, 1);
        }

        private PublicAndPrivateConstructor(String collaborator, int seam) {}
    }

    /** Meta-annotated stereotype (@Service is a @Component). */
    @Service
    public static class ServiceWithTwoConstructors {
        public ServiceWithTwoConstructors(String a) {}

        public ServiceWithTwoConstructors(String a, String b) {}
    }

    /** Records get no special treatment from component scanning. */
    @Component
    public record RecordComponentWithTwoConstructors(String a, String b) {
        public RecordComponentWithTwoConstructors(String a) {
            this(a, "default");
        }
    }

    // ---- accepted by SPRING_CAN_CHOOSE_A_CONSTRUCTOR --------------------------------------

    @Component
    public static class TwoConstructorsOneAutowired {
        @Autowired
        public TwoConstructorsOneAutowired(String collaborator) {
            this(collaborator, 1);
        }

        TwoConstructorsOneAutowired(String collaborator, int seam) {}
    }

    /** Spring falls back to the no-arg constructor; legal (rule 3 checks what it ignores). */
    @Component
    public static class TwoConstructorsWithNoArg {
        public TwoConstructorsWithNoArg() {}

        TwoConstructorsWithNoArg(String seam) {}
    }

    @Component
    public static class SingleConstructor {
        public SingleConstructor(String a, String b) {}
    }

    /** Not a candidate: component scanning skips abstract classes. */
    @Component
    public abstract static class AbstractComponent {
        protected AbstractComponent(String a) {}

        protected AbstractComponent(String a, String b) {}
    }

    /** Not a candidate: component scanning skips inner (non-static nested) classes. */
    @Component
    public class InnerComponent {
        public InnerComponent(String a) {}

        public InnerComponent(String a, String b) {}
    }

    /** Not a bean at all: no stereotype. */
    public static class PlainClass {
        public PlainClass(String a) {}

        public PlainClass(String a, String b) {}
    }

    // ---- CONFIGURATION_PROPERTIES_HAVE_A_BINDABLE_CONSTRUCTOR -----------------------------

    /** Reported: Boot binds JavaBean style and there is no no-arg constructor. */
    @ConfigurationProperties("fixture.unbindable")
    public static class PropertiesWithTwoConstructors {
        public PropertiesWithTwoConstructors(String a) {}

        public PropertiesWithTwoConstructors(String a, String b) {}
    }

    @ConfigurationProperties("fixture.bindable")
    public static class PropertiesWithConstructorBinding {
        @ConstructorBinding
        public PropertiesWithConstructorBinding(String a) {}

        public PropertiesWithConstructorBinding(String a, String b) {}
    }

    @ConfigurationProperties("fixture.record")
    public record PropertiesRecord(String a) {}

    // ---- NO_SILENT_NO_ARG_FALLBACK --------------------------------------------------------

    /** Reported: the McpStdioCommandGuard shape - the @Value constructor is never used. */
    @Component
    public static class NoArgShadowsValueConstructor {
        public NoArgShadowsValueConstructor() {}

        public NoArgShadowsValueConstructor(@Value("${fixture.list:}") String list) {}
    }

    @Component
    public static class NoArgAndAutowiredValueConstructor {
        public NoArgAndAutowiredValueConstructor() {}

        @Autowired
        public NoArgAndAutowiredValueConstructor(@Value("${fixture.list:}") String list) {}
    }
}
