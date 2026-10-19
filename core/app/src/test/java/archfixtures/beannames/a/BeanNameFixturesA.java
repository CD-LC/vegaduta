package archfixtures.beannames.a;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/**
 * Bytecode fixtures for {@code UniqueBeanNamesTest}, half A; {@code beannames.b} declares the other
 * half. Outside {@code com.agenticai.core}, so no Spring context scans them. Only read as bytecode.
 */
public final class BeanNameFixturesA {
    private BeanNameFixturesA() {}
}

/** Clashes with b.DuplicateService (same default name, different packages). */
@Service
class DuplicateService {}

/** An explicit name takes part too: clashes with b.ExplicitlyNamedService's default name. */
@Service("explicitlyNamedService")
class SomeOtherService {}

/** Spring Data repositories share the bean namespace: clashes with b.DuplicateRepository. */
interface DuplicateRepository extends Repository<DuplicateEntity, Long> {}

@Configuration
class BeansA {
    /** Clashes with BeansB#duplicateBean. */
    @Bean
    Object duplicateBean() {
        return new Object();
    }

    /** Conditional alternatives may share a name: not reported. */
    @Bean
    @ConditionalOnProperty("fixture.a")
    Object conditionalBean() {
        return new Object();
    }
}

class Holder {
    /** Spring names this "holder.Nested"; b's is "otherHolder.Nested": no clash. */
    @Component
    static class Nested {}
}

/** Clashes with b.DuplicateEntity (same unqualified class name). */
@Entity
class DuplicateEntity {
    @Id Long id;
}

/** An explicit entity name takes part too: clashes with b.RenamedEntity. */
@Entity(name = "RenamedEntity")
class EntityWithExplicitName {
    @Id Long id;
}
