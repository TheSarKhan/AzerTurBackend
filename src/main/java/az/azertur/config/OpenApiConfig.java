package az.azertur.config;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.*;
@Configuration public class OpenApiConfig {
 @Bean OpenAPI openAPI(){return new OpenAPI().info(new Info().title("Azertur API").version("v1").description("Customer catalogue, sample quotes and protected operations foundation.")).components(new Components().addSecuritySchemes("operationsAuth",new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")));}
}
