package archfixtures.shapes;

import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

/**
 * Beans for {@code ShapeInjectionPointsTest}'s negative tests: the bean graph of a shape that scans
 * no REST controllers (jobs, worker). They live outside {@code com.agenticai.core} so no Spring
 * context in the suite scans them. {@code SpecProvider} is implemented only by a controller (the
 * real-world shape of the bug: {@code ApiSpecProvider} and {@code ApiSpecController}).
 */
public final class ShapeFixtures {

    private ShapeFixtures() {}

    public interface SpecProvider {
        String spec();
    }

    public interface OtherProvider {}

    public interface BeanMethodProvider {}

    @RestController
    public static class SpecController implements SpecProvider {
        @Override
        public String spec() {
            return "{}";
        }
    }

    @Controller
    public static class PlainController {}

    /** A required constructor parameter only a controller can satisfy: the bug. */
    @Component
    public static class RequiresControllerProvidedBean {
        public RequiresControllerProvidedBean(SpecProvider spec) {}
    }

    /** Same through a field. */
    @Service
    public static class FieldRequiresControllerProvidedBean {
        @Autowired private SpecProvider spec;
    }

    /** Same through a {@code @Bean} method parameter. */
    @Configuration
    public static class BeanMethodRequiresControllerProvidedBean {
        @Bean
        public Object needsSpec(SpecProvider spec) {
            return new Object();
        }
    }

    /** Needs a bean nothing provides at all. */
    @Component
    public static class RequiresAbsentBean {
        public RequiresAbsentBean(OtherProvider other) {}
    }

    /** The fix: an optional lookup. */
    @Component
    public static class OptionalThroughObjectProvider {
        public OptionalThroughObjectProvider(ObjectProvider<SpecProvider> spec) {}
    }

    @Component
    public static class OptionalThroughOptional {
        public OptionalThroughOptional(Optional<SpecProvider> spec) {}
    }

    @Component
    public static class OptionalThroughNotRequiredField {
        @Autowired(required = false)
        private SpecProvider spec;
    }

    @Component
    public static class OptionalThroughCollection {
        public OptionalThroughCollection(List<SpecProvider> specs) {}
    }

    @Component
    public static class ValueParameterIsNotAnInjectionPoint {
        public ValueParameterIsNotAnInjectionPoint(@Value("${x:y}") SpecProvider ignored) {}
    }

    /** Satisfied by a component that is not a controller. */
    @Component
    public static class PlainProvider implements OtherProvider {}

    @Component
    public static class RequiresPlainProvider {
        public RequiresPlainProvider(OtherProvider other) {}
    }

    /** Satisfied by a {@code @Bean} method. */
    @Configuration
    public static class ProvidesByBeanMethod {
        @Bean
        public BeanMethodProvider beanMethodProvider() {
            return new BeanMethodProvider() {};
        }
    }

    @Component
    public static class RequiresBeanMethodProvider {
        public RequiresBeanMethodProvider(BeanMethodProvider provider) {}
    }

    /** A controller may require a controller-provided bean: it is not in the shape either. */
    @RestController
    public static class ControllerRequiresSpec {
        public ControllerRequiresSpec(SpecProvider spec) {}
    }
}
