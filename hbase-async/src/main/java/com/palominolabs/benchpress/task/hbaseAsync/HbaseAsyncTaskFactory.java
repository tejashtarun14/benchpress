package com.palominolabs.benchpress.task.hbaseAsync;

import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.palominolabs.benchpress.job.key.KeyGeneratorFactory;
import com.palominolabs.benchpress.job.task.TaskFactory;
import com.palominolabs.benchpress.job.task.TaskOperation;
import com.palominolabs.benchpress.job.value.ValueGeneratorFactory;
import com.palominolabs.benchpress.task.reporting.TaskProgressClient;
import org.hbase.async.HBaseClient;
import org.hbase.async.TableNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

final class HbaseAsyncTaskFactory implements TaskFactory {

    private static final Logger logger = LoggerFactory.getLogger(HbaseAsyncTaskFactory.class);

    private final String zkQuorum;
    private final String columnFamily;
    private final String table;
    private final String qualifier;
    private HBaseClient client;

    public HbaseAsyncTaskFactory(String zkQuorum, String table, String columnFamily, String qualifier) {
        this.columnFamily = columnFamily;
        this.zkQuorum = zkQuorum;
        this.table = table;
        this.qualifier = qualifier;
    }

    @Override
    public Collection<Runnable> getRunnables(KeyGeneratorFactory keyGeneratorFactory,
        ValueGeneratorFactory valueGeneratorFactory, TaskOperation taskOperation, int numThreads, int numQuanta,
        int batchSize, UUID workerId, int partitionId, TaskProgressClient taskProgressClient, UUID jobId,
        int progressReportInterval, AtomicInteger reportSequenceCounter) throws IOException {
        List<Runnable> runnables = Lists.newArrayList();

        client = new HBaseClient(zkQuorum);

        try {
            client.ensureTableExists(table).joinUninterruptibly();
        } catch (TableNotFoundException e) {
            logger.warn("Table " + table + " doesn't exist", e);
            throw Throwables.propagate(e);
        } catch (Exception e) {
            logger.warn("Couldn't check if table exists", e);
            throw Throwables.propagate(e);
        }

        int quantaPerThread = numQuanta / numThreads;

        for (int i = 0; i < numThreads; i++) {
            runnables.add(new HbaseAsyncRunnable(client, quantaPerThread, table, columnFamily, qualifier,
                keyGeneratorFactory.getKeyGenerator(), valueGeneratorFactory.getValueGenerator(), workerId, partitionId,
                batchSize, progressReportInterval, taskProgressClient, jobId, reportSequenceCounter));
        }
        return runnables;
    }

    @Override
    public void shutdown() {
        client.shutdown();
    }
}
