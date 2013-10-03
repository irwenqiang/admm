package com.intentmedia.admm;

import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Logger;

import static com.intentmedia.admm.AdmmIterationHelper.admmMapperContextToJson;
import static com.intentmedia.admm.AdmmIterationHelper.mapToJson;

public class AdmmIterationReducer extends MapReduceBase implements Reducer<IntWritable, Text, IntWritable, Text> {

    private static final double THRESHOLD = 0.0001;
    private static final Logger LOG = Logger.getLogger(AdmmIterationReducer.class.getName());
    private static final IntWritable ZERO = new IntWritable(0);
    private Map<String, String> outputMap = new HashMap<String, String>();
    private int iteration;
    private int numberOfMappers;
    private boolean regularizeIntercept;

    private long reduceStartTime;
    private long firstReduceCompleted;
    private long lastReduceCompleted;

    @Override
    public void configure(JobConf job) {
        super.configure(job);
        iteration = Integer.parseInt(job.get("iteration.number"));
        regularizeIntercept = job.getBoolean("regularize.intercept", false);
        numberOfMappers = job.getNumMapTasks();
    }

    @Override
    public void reduce(IntWritable key, Iterator<Text> values, OutputCollector<IntWritable, Text> output, Reporter reporter)
            throws IOException {
        reduceStartTime = System.nanoTime();
        AdmmReducerContextGroup context = new AdmmReducerContextGroup(values, numberOfMappers, LOG, iteration);
        setOutputMapperValues(context);
        output.collect(ZERO, new Text(mapToJson(outputMap)));

        if (context.getRNorm() > THRESHOLD || context.getSNorm() > THRESHOLD) {
            reporter.getCounter(IterationCounter.ITERATION).increment(1);
        }
    }

    private void setOutputMapperValues(AdmmReducerContextGroup context) throws IOException {
        double[] zUpdated = getZUpdated(context);
        double[][] xUpdated = context.getXUpdated();
        String[] splitIds = context.getSplitIds();

        for (int mapperNumber = 0; mapperNumber < context.getNumberOfMappers(); mapperNumber++) {
            if(mapperNumber == 0) {
                firstReduceCompleted = System.nanoTime();
            }
            double[] uUpdated = getUUpdated(context, mapperNumber, zUpdated);
            lastReduceCompleted = System.nanoTime();
            AdmmMapperContext admmMapperContext =
                    new AdmmMapperContext(null, null, uUpdated, xUpdated[mapperNumber], zUpdated,
                            context.getRho() * context.getRhoMultiplier(),
                            context.getLambda(), context.getPrimalObjectiveValue(),
                            context.getRNorm(), context.getSNorm(),
                            context.getMapStartTime(), context.getOptimizationStartTime(),
                            context.getMapEndTime(), reduceStartTime,
                            firstReduceCompleted, lastReduceCompleted);
            String currentSplitId = splitIds[mapperNumber];
            outputMap.put(currentSplitId, admmMapperContextToJson(admmMapperContext));
            LOG.info("Iteration " + iteration + " Reducer Setting splitID " + currentSplitId);
        }
    }

    private double[] getZUpdated(AdmmReducerContextGroup context) {
        int numMappers = context.getNumberOfMappers();
        int numFeatures = context.getNumberOfFeatures();

        double[] xAverage = context.getXUpdatedAverage();
        double[] uAverage = context.getUInitialAverage();
        double[] zUpdated = new double[numFeatures];
        double zMultiplier = (numMappers * context.getRho()) / (2 * context.getLambda() + numMappers * context.getRho());

        for (int i = 0; i < numFeatures; i++) {
            if (i == 0 && !regularizeIntercept) {
                zUpdated[i] = xAverage[i] + uAverage[i];
            }
            else {
                zUpdated[i] = zMultiplier * (xAverage[i] + uAverage[i]);
            }
        }

        return zUpdated;
    }

    private double[] getUUpdated(AdmmReducerContextGroup context, int mapperNumber, double[] zUpdated) {
        int numFeatures = context.getNumberOfFeatures();
        double[] uInitial = context.getUInitial()[mapperNumber];
        double[] xUpdated = context.getXUpdated()[mapperNumber];
        double[] uUpdated = new double[numFeatures];
        double rhoMultiplier = context.getRhoMultiplier();

        for (int i = 0; i < numFeatures; i++) {
            uUpdated[i] = (1 / rhoMultiplier) * (uInitial[i] + xUpdated[i] - zUpdated[i]);
        }
        return uUpdated;
    }

    public static enum IterationCounter {
        ITERATION
    }
}
