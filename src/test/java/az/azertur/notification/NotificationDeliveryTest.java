package az.azertur.notification;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
class NotificationDeliveryTest {
 @Test void missingConfigurationNeverSends(){var db=mock(JdbcTemplate.class);var mail=mock(JavaMailSender.class);var delivery=new NotificationDelivery(db,mail,false,"");assertThrows(RuntimeException.class,()->delivery.send(UUID.randomUUID()));verifyNoInteractions(mail,db);}
 @Test void duplicateOrAlreadyClaimedJobNeverSends(){var db=mock(JdbcTemplate.class);var mail=mock(JavaMailSender.class);new NotificationDelivery(db,mail,true,"qa@example.com").send(UUID.randomUUID());verifyNoInteractions(mail);}
 @Test void ambiguousSmtpFailureIsNotQueuedAgain(){var db=mock(JdbcTemplate.class);var mail=mock(JavaMailSender.class);UUID id=UUID.randomUUID();when(db.update(anyString(),eq(id))).thenReturn(1);when(db.queryForMap(anyString(),eq(id))).thenReturn(Map.of("email","qa@example.com","locale","az","booking_id",UUID.randomUUID()));doThrow(new MailSendException("connection lost after DATA")).when(mail).send(any(SimpleMailMessage.class));new NotificationDelivery(db,mail,true,"qa@example.com").send(id);verify(db).update(contains("status='DELIVERY_UNKNOWN'"),eq(id));verify(mail,times(1)).send(any(SimpleMailMessage.class));}
 @Test void successfulSmtpAcknowledgementIsRecorded(){var db=mock(JdbcTemplate.class);var mail=mock(JavaMailSender.class);UUID id=UUID.randomUUID();when(db.update(anyString(),eq(id))).thenReturn(1);when(db.queryForMap(anyString(),eq(id))).thenReturn(Map.of("email","qa@example.com","locale","en","booking_id",UUID.randomUUID()));new NotificationDelivery(db,mail,true,"qa@example.com").send(id);verify(db).update(contains("status='SENT',sent_at"),eq(id));var message=ArgumentCaptor.forClass(SimpleMailMessage.class);verify(mail).send(message.capture());assertArrayEquals(new String[]{"qa@example.com"},message.getValue().getTo());assertFalse(message.getValue().getText().contains("PRIVATE"));}
}
