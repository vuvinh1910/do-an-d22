package com.platform.worker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerApplicationTest {

    @Test
    void applicationDeclaresSpringBootConfiguration() {
        assertTrue(WorkerApplication.class.isAnnotationPresent(SpringBootApplication.class));
    }

    @Test
    void applicationProvidesPublicStaticMainEntryPoint() throws NoSuchMethodException {
        Method main = WorkerApplication.class.getDeclaredMethod("main", String[].class);

        assertTrue(Modifier.isPublic(main.getModifiers()));
        assertTrue(Modifier.isStatic(main.getModifiers()));
        assertEquals(void.class, main.getReturnType());
    }
}