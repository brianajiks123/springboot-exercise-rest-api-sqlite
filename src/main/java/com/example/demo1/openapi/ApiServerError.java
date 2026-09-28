package com.example.demo1.openapi;

import com.example.demo1.dto.ApiErrorResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@ApiResponse(responseCode = "500", description = "Unexpected server error; the real cause is logged, never returned", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
public @interface ApiServerError {
}
