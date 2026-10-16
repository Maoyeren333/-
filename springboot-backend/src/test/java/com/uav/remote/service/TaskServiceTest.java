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
            assertThrows(IllegalArgumentException.class, () -> service.resolveInputs(1L, algorithm, null, "image.png"));
        }
        verifyNoInteractions(ai);
    }

    @Test
    void usesAllUploadedImagesAndRejectsMissingInput() {
        when(images.findByTaskIdOrderByIdAsc(1L)).thenReturn(List.of());
        assertThrows(IllegalArgumentException.class, () -> service.resolveInputs(1L, ALGORITHM, null, null));
        RemoteImage first = new RemoteImage();
        first.setFilePath("first.png");
        RemoteImage second = new RemoteImage();
        second.setFilePath("second.png");
        when(images.findByTaskIdOrderByIdAsc(1L)).thenReturn(List.of(first, second));
        assertEquals(List.of("first.png", "second.png"), service.resolveInputs(1L, ALGORITHM, null, null));
        assertEquals(List.of("explicit.png"), service.resolveInputs(1L, ALGORITHM, null, "explicit.png"));
    }

    @Test
    void canSelectSecondImageAndRejectForeignOrEmptySelection() {
        RemoteImage first = new RemoteImage(); first.setId(10L); first.setFilePath("first.png");
        RemoteImage second = new RemoteImage(); second.setId(20L); second.setFilePath("second.png");
        when(images.findByTaskIdOrderByIdAsc(1L)).thenReturn(List.of(first, second));
        assertEquals(List.of("second.png"), service.resolveInputs(1L, ALGORITHM, List.of(20L), null));
        assertEquals(List.of("second.png", "first.png"), service.resolveInputs(1L, ALGORITHM, List.of(20L, 10L, 20L), null));
        assertThrows(IllegalArgumentException.class, () -> service.resolveInputs(1L, ALGORITHM, List.of(99L), null));
        assertThrows(IllegalArgumentException.class, () -> service.resolveInputs(1L, ALGORITHM, List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> service.resolveInputs(1L, ALGORITHM, List.of(20L), "first.png"));
    }

    @Test
    void batchProducesSeparateResultsAndContinuesAfterFailure() {
        when(ai.infer(eq(ALGORITHM), anyMap())).thenAnswer(invocation -> {
            Map<String, Object> body = invocation.getArgument(1);
            String source = (String) body.get("image_path");
            if ("broken.png".equals(source)) throw new RuntimeException("Unreadable test image");
            return Map.of("mask_path", source + ".tif", "weights_loaded", true, "simulated", false);
        });
        service.interpret(1L, ALGORITHM, List.of("first.png", "broken.png", "second.png"));
        verify(ai).infer(ALGORITHM, Map.of("image_path", "first.png"));
        verify(ai).infer(ALGORITHM, Map.of("image_path", "second.png"));
        ArgumentCaptor<Interpretation> saved = ArgumentCaptor.forClass(Interpretation.class);
        verify(results, times(6)).save(saved.capture());
        List<Interpretation> distinct = saved.getAllValues().stream().distinct().toList();
        assertEquals(3, distinct.size());
        assertEquals(List.of("first.png", "broken.png", "second.png"), distinct.stream().map(Interpretation::getSourcePath).toList());
        assertEquals(List.of("SUCCESS", "FAILED", "SUCCESS"), distinct.stream().map(Interpretation::getStatus).toList());
        assertEquals("second.png.tif", distinct.get(2).getMaskPath());
        assertEquals("FAILED", task.getStatus());
    }

    @Test
    void successfulBatchCompletesAfterEveryImage() {
        when(ai.infer(eq(ALGORITHM), anyMap())).thenReturn(Map.of(
            "mask_path", "result.tif", "weights_loaded", true, "simulated", false));
        service.interpret(1L, ALGORITHM, List.of("first.png", "second.png"));
        verify(ai, times(2)).infer(eq(ALGORITHM), anyMap());
        assertEquals("COMPLETED", task.getStatus());
    }

    @Test
    void realWeightedOutputCompletesTask() {
        when(ai.infer(eq(ALGORITHM), anyMap())).thenReturn(Map.of(
            "mask_path", "result.tif", "weights_loaded", true, "simulated", false));
        service.interpret(1L, ALGORITHM, List.of("first.png"));
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
            service.interpret(1L, ALGORITHM, List.of("first.png"));
            assertEquals("FAILED", task.getStatus());
        }
    }

    @Test
    void inferenceExceptionFailsTask() {
        when(ai.infer(eq(ALGORITHM), anyMap())).thenThrow(new RuntimeException("weights unavailable"));
        service.interpret(1L, ALGORITHM, List.of("first.png"));
        assertEquals("FAILED", task.getStatus());
    }
}
