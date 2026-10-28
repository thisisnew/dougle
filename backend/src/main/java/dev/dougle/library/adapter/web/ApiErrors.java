package dev.dougle.library.adapter.web;

import dev.dougle.library.application.LibraryException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(LibraryException.class)
    ProblemDetail library(LibraryException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(exception.status()), exception.getMessage());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ProblemDetail conflict() {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(409), "같은 위치에 동일한 이름이 이미 존재합니다.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid() {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(400), "이름을 입력하세요.");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail tooLarge() {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(413), "파일은 10 MiB 이하여야 합니다.");
    }
}
