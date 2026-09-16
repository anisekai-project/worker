package fr.anisekai.worker.factory;

import fr.anisekai.scheduler.commons.interfaces.ObjectSerializer;
import fr.anisekai.scheduler.tasking.data.TaskMeta;
import fr.anisekai.scheduler.tasking.interfaces.factories.ClientFactory;
import fr.anisekai.scheduler.tasking.interfaces.structure.TaskHandler;
import fr.anisekai.wireless.tasks.conversion.AbstractMediaConversionClientFactory;
import fr.anisekai.wireless.tasks.conversion.MediaConversionInput;
import fr.anisekai.wireless.tasks.conversion.MediaConversionOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Client factory for remote media conversion.
 * <p>
 * This factory provides serialization/deserialization for the scheduler registry.
 * The actual task execution is handled by the worker loop, not by the scheduler's
 * execution mechanism.
 */
public class RemoteMediaConversionClientFactory extends AbstractMediaConversionClientFactory {

    private static final Logger LOG = LoggerFactory.getLogger(RemoteMediaConversionClientFactory.class);

    public RemoteMediaConversionClientFactory(fr.anisekai.scheduler.commons.interfaces.ObjectSerializer<fr.anisekai.wireless.tasks.conversion.MediaConversionInput> argumentsSerializer,
                                               fr.anisekai.scheduler.commons.interfaces.ObjectSerializer<fr.anisekai.wireless.tasks.conversion.MediaConversionOutput> resultSerializer) {
        super(argumentsSerializer, resultSerializer);
    }

    @Override
    public String getName() {
        return "media:convert";
    }

    @Override
    public fr.anisekai.scheduler.tasking.interfaces.structure.TaskHandler<fr.anisekai.wireless.tasks.conversion.MediaConversionInput, fr.anisekai.wireless.tasks.conversion.MediaConversionOutput> getHandler() {
        // The worker loop handles execution directly, not through the scheduler's handler mechanism.
        // This factory is registered only for serialization purposes.
        throw new UnsupportedOperationException("Worker handles execution directly; use WorkerLoop for task execution");
    }

    @Override
    public MediaConversionOutput run(TaskMeta meta) {
        UUID taskId = meta.identifier();
        LOG.info("Executing media conversion task {}", taskId);

        try {
            MediaConversionInput input = getArgumentsSerializer().deserialize(meta.arguments());
            // The actual conversion is handled by the worker loop which has the ApiClient and isolation context
            throw new UnsupportedOperationException("Direct factory execution not supported - use worker loop for execution with isolation context");
        } catch (Exception e) {
            LOG.error("Conversion task {} failed: {}", taskId, e.getMessage(), e);
            throw new RuntimeException("Conversion failed", e);
        }
    }
}