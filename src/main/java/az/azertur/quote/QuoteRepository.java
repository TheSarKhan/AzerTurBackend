package az.azertur.quote;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuoteRepository extends JpaRepository<QuoteEntity, UUID> {
  List<QuoteEntity> findTop20ByOrderByCreatedAtDesc();
}
