package io.aitest.workbench.api;

import io.aitest.workbench.infrastructure.Store.Problem;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.*;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(Problem.class)public ResponseEntity<?> problem(Problem p){return response(p.status,p.code);}
    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class})public ResponseEntity<?> invalid(Exception e){return response(422,"VALIDATION_FAILED");}
    @ExceptionHandler(DataAccessException.class)public ResponseEntity<?> database(DataAccessException e){
        if(e instanceof org.springframework.dao.DataIntegrityViolationException)return response(409,"DATABASE_CONSTRAINT_CONFLICT");
        if(e instanceof org.springframework.dao.TransientDataAccessException)return response(503,"DATABASE_TRANSIENT_RECONCILE");
        return response(503,"DATABASE_UNAVAILABLE_RECONCILE");
    }
    private ResponseEntity<?> response(int status,String code){return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL,"no-store").body(Map.of("error",Map.of("code",code,"retryAdvice",status==409||status==503?"reconcile":"correct_input"),"correlationId",UUID.randomUUID().toString()));}
}
