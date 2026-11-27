package io.portfolio;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
class Problems {
    static ResponseStatusException of(HttpStatus code, String reason) { return new ResponseStatusException(code, reason); }
    static ResponseStatusException bad(String reason) { return of(HttpStatus.BAD_REQUEST, reason); }
    static ResponseStatusException missing() { return of(HttpStatus.NOT_FOUND, "Record not found."); }
    static ResponseStatusException forbidden() { return of(HttpStatus.FORBIDDEN, "This account cannot perform that action."); }
    static ResponseStatusException conflict(String reason) { return of(HttpStatus.CONFLICT, reason); }
    @ExceptionHandler(ResponseStatusException.class) ProblemDetail status(ResponseStatusException failure) { return failure.getBody(); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail duplicate() { return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "This record already exists or changed."); }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail malformed() { return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Check the submitted values."); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException error) {
        var field = error.getBindingResult().getFieldError();
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, field == null ? "Check your input." : field.getField() + " " + field.getDefaultMessage());
    }
}
