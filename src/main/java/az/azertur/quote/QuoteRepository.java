package az.azertur.quote;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface QuoteRepository extends JpaRepository<QuoteEntity,UUID>{List<QuoteEntity> findTop20ByOrderByCreatedAtDesc();}
