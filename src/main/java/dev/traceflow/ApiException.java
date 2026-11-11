package dev.traceflow;
public class ApiException extends RuntimeException {
 final int status; final String code;
 public ApiException(int status,String code,String message) { super(message);this.status=status;this.code=code; }
 static ApiException missing(String type) { return new ApiException(404,"NOT_FOUND","记录不存在或无权访问"); }
 static ApiException conflict(String message) { return new ApiException(409,"VERSION_CONFLICT",message); }
}
