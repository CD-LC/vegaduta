package archfixtures.beannames.b;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/** Bytecode fixtures for {@code UniqueBeanNamesTest}, half B; see {@code beannames.a}. */
public final class BeanNameFixturesB {
    private BeanNameFixturesB() {}
}

@Service
class DuplicateService {}

@Service
class ExplicitlyNamedService {}

interface DuplicateRepository extends Repository<DuplicateEntity, Long> {}

/** Not a bean: a base interface Spring Data skips. */
@NoRepositoryBean
interface BaseRepository<T> extends Repository<T, Long> {}

/** Unique name; extends a @NoRepositoryBean base, still a bean. */
interface UniqueRepository extends BaseRepository<DuplicateEntity> {}

@Configuration
class BeansB {
    @Bean
    Object duplicateBean() {
        return new Object();
    }

    @Bean
    @ConditionalOnProperty("fixture.b")
    Object conditionalBean() {
        return new Object();
    }

    /** Overloads of one @Bean method are one definition: not a clash with itself. */
    @Bean
    Object overloadedBean() {
        return new Object();
    }

    Object overloadedBean(String unused) {
        return unused;
    }
}

class OtherHolder {
    @Component
    static class Nested {}
}

@Entity
class DuplicateEntity {
    @Id Long id;
}

@Entity
class RenamedEntity {
    @Id Long id;
}
