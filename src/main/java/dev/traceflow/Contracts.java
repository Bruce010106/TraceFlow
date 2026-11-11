package dev.traceflow;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
public final class Contracts {
 private Contracts() {}
 public record ProjectInput(@NotBlank @Size(max=120) String name,@NotBlank @Size(max=60) String timezone) {}
 public record DocumentInput(@NotBlank @Size(max=200) String title,@NotBlank @Size(max=10000) String content,@NotNull LocalDate referenceDate) {}
 public record ApprovalInput(@NotBlank @Size(max=200) String title,@Size(max=120) String assigneeName,LocalDate dueDate,@PositiveOrZero Long expectedTaskVersion) {}
 public record TaskInput(@NotBlank @Size(max=200) String title,@Size(max=120) String assigneeName,LocalDate dueDate,@NotNull TaskStatus status,@NotNull @PositiveOrZero Long expectedTaskVersion) {}
 public enum TaskStatus { OPEN,IN_PROGRESS,DONE,CANCELLED }
}
