package dev.traceflow;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice
public class ApiErrors {
 @ExceptionHandler(ApiException.class)
 ResponseEntity<?> business(ApiException e) { return ResponseEntity.status(e.status).body(Map.of("code",e.code,"message",e.getMessage())); }
 @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
 ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("code","INVALID_INPUT","message","请检查请求字段、长度、日期与版本")); }
}
