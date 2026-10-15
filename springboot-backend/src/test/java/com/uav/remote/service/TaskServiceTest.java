package com.uav.remote.service;

import com.uav.remote.entity.Interpretation;
import com.uav.remote.entity.RemoteImage;
import com.uav.remote.entity.Task;
import com.uav.remote.feign.InferenceClient;
import com.uav.remote.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskServiceTest {
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final InterpretationRepository results = mock(InterpretationRepository.class);
    private final RemoteImageRepository images = mock(RemoteImageRepository.class);
    private final InferenceClient ai = mock(InferenceClient.class);
    private final TaskService service = new TaskService(tasks, results, images, ai);
    private Task task;
    private static final String ALGORITHM = "camouflaged_object_detection";

    @BeforeEach
    void setup() {
        task = new Task();
        task.setId(1L);
        task.setAlgorithm(ALGORITHM);
        when(tasks.findById(1L)).thenReturn(Optional.of(task));
        when(results.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void rejectsRetiredAlgorithmsBeforeSubmission() {
        for (String algorithm : List.of("change_detection", "semantic_segmentation")) {
            task.setAlgorithm(algorithm);
            assertThrows(IllegalArgumentException.class, () -> service.create(task));
            assertThrows(IllegalArgumentException.class, () -> service.resolveInput(1L, algorithm, "image.png"));
        }
        verifyNoInteractions(ai);
    }

    @Test
    void usesFirstUploadedImageAndRejectsMissingInput() {
        when(images.findByTaskIdOrderByIdAsc(1L)).thenReturn(List.of());
        assertThrows(IllegalArgumentException.class, () -> service.resolveInput(1L, ALGORITHM, null));
        RemoteImage first = new RemoteImage();
        first.setFilePath("first.png");
        RemoteImage second = new RemoteImage();
        second.setFilePath("second.png");
        when(images.findByTaskIdOrderByIdAsc(1L)).thenReturn(List.of(first, second));
        assertEquals("first.png", service.resolveInput(1L, ALGORITHM, null));
        assertEquals("explicit.png", service.resolveInput(1L, ALGORITHM, "explicit.png"));
    }

    @Test
    void realWeightedOutputCompletesTask() {
        when(ai.infer(eq(ALGORITHM), anyMap())).thenReturn(Map.of(
            "mask_path", "result.tif", "weights_loaded", true, "simulated", false));
        service.interpret(1L, ALGORITHM, "first.png");
        assertEquals("COMPLETED", task.getStatus());
        verify(ai).infer(ALGORITHM, Map.of("image_path", "first.png"));
        ArgumentCaptor<Interpretation> saved = ArgumentCaptor.forClass(Interpretation.class);
        verify(results, atLeastOnce()).save(saved.capture());
        assertEquals("SUCCESS", saved.getValue().getStatus());
        assertEquals("result.tif", saved.getValue().getMaskPath());
    }

    @Test
    void simulatedOrUnweightedOutputFails() {
        for (Map<String, Object> output : List.<Map<String, Object>>of(
            Map.of("mask_path", "result.tif", "weights_loaded", false, "simulated", false),
            Map.of("mask_path", "result.tif", "weights_loaded", true, "simulated", true),
            Map.of("mask_path", "result.tif"),
            Map.of("mask_path", "", "weights_loaded", true, "simulated", false))) {
            when(ai.infer(eq(ALGORITHM), anyMap())).thenReturn(output);
            service.interpret(1L, ALGORITHM, "first.png");
            assertEquals("FAILED", task.getStatus());
        }
    }

    @Test
    void inferenceExceptionFailsTask() {
        when(ai.infer(eq(ALGORITHM), anyMap())).thenThrow(new RuntimeException("weights unavailable"));
        service.interpret(1L, ALGORITHM, "first.png");
        assertEquals("FAILED", task.getStatus());
    }
}
