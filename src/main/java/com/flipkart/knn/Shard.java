package com.flipkart.knn;

import com.opencsv.CSVReader;

import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class Shard {
    ExecutorService executorService;

    Shard(int threadCount, int embeddingSize) {
        this.threadCount = threadCount;
        this.embeddingSize = embeddingSize;
        this.executorService = Executors.newWorkStealingPool(threadCount);
    }

    public void insert(String id, float[] embedding) {
        ids.add(id);
        embeddings.put(id, embedding);
        countDataPoints++;
    }

    public int getCountDataPoints() {return countDataPoints;}

    public ArrayList<ResultRecord> knn(float[] query, int k) throws InterruptedException, ExecutionException {
        int totalWorkload = ids.size();
        int workload = totalWorkload/ threadCount;
        int assignedWorkload = 0;
        ArrayList<Worker> workers = new ArrayList<Worker>();
        ArrayList<Future> futures = new ArrayList<Future>();
        for(int i = 0; i < threadCount; i++) {
            int endpoint = assignedWorkload + workload;
            if (assignedWorkload + workload > totalWorkload)
                endpoint = totalWorkload;
            Worker currWorker = new Worker(assignedWorkload, endpoint, flatEmbeddings, flatIds, query, k);
            futures.add(executorService.submit(currWorker));
            workers.add(currWorker);
            assignedWorkload += workload;
        }
        //block until all tasks are finished
        for(Future future : futures) {
            future.get();
        }

        PriorityQueue<ResultRecord> minGlobal = new PriorityQueue(k+2, ResultRecordComparator.INSTANCE);
        for (Worker worker: workers) {
            for(ResultRecord currRecord: worker.localMin) {
                if (minGlobal.size() < k) {
                    minGlobal.add(currRecord);
                }
                else {
                    ResultRecord last = minGlobal.peek();
                    if (last.distance > currRecord.distance) {
                        minGlobal.poll();
                        minGlobal.add(currRecord);
                    }
                }
            }
        }

        ArrayList<ResultRecord> result = new ArrayList<ResultRecord>();
        for(ResultRecord currRecord : minGlobal) {
            result.add(currRecord);
        }

        return result;
    }

    public void loadFromFile(String path) throws IOException {
        CSVReader reader = new CSVReader(new FileReader(path));
        String [] nextLine;
        while ((nextLine = reader.readNext()) != null) {
            String id = null;
            float[] currEmbedding = new float[this.embeddingSize];
            for(int i = 0; i < nextLine.length; i++) {
                if (i == 0) {
                    id = nextLine[i];
                }
                else {
                    currEmbedding[i-1] = Float.parseFloat(nextLine[i]);
                }
            }
            insert(id, currEmbedding);
        }
    }

    public void index() {
        int countEmbeddings = embeddings.size();
        flatEmbeddings = new float[countEmbeddings][];
        flatIds = new String[countEmbeddings];
        int counter = 0;
        for(Map.Entry<String, float[]> entry: embeddings.entrySet()) {
            flatIds[counter] = entry.getKey();
            flatEmbeddings[counter] = entry.getValue();
            counter++;
        }
    }

    public ArrayList<ResultRecord> loadTest(float[] embedding, int k) throws InterruptedException, ExecutionException {
        return knn(embedding, k);
    }

    public float[] getRandomEmbedding() {
        Random randomGenerator = new Random();
        randomGenerator.setSeed(42);
        int index = randomGenerator.nextInt(ids.size());
        String id = ids.get(index);
        float[] embedding = embeddings.get(id);
        return embedding;
    }

    class Worker implements Runnable {
        Worker(int start, int end, float[][] flatEmbeddings, String[] flatIds, float[] query, int k) {
            this.start = start;
            this.end = end;
            this.flatEmbeddings = flatEmbeddings;
            this.flatIds = flatIds;
            this.query = query;
            this.k = k;
            localMin = new PriorityQueue(this.k+2, ResultRecordComparator.INSTANCE);
        }

        public void run() {
            for(int i = start; i < end; i++) {
                String currId = flatIds[i];
                float[] currEmbedding = flatEmbeddings[i];
                double currDistance = euclidianDistance(currEmbedding);
                if (localMin.size() < k) {
                    ResultRecord currResultRecord = new ResultRecord(currId, currDistance);
                    localMin.add(currResultRecord);
                }
                else {
                    ResultRecord last = localMin.peek();
                    if (last.distance > currDistance) {
                        ResultRecord currResultRecord = new ResultRecord(currId, currDistance);
                        localMin.poll();
                        localMin.add(currResultRecord);
                    }
                }
            }
        }

        public double getMin() {return min;}

        private double euclidianDistance(float[] given) {
            float total = 0;
            for(int i = 0; i < given.length; i++) {
                float a = given[i];
                float b = query[i];
                float c = a - b;
                total +=  c * c;
            }
            return total;
        }

        private int start;
        private int end;
        private float[][] flatEmbeddings;
        private String[] flatIds;
        private float[] query;
        private double min;
        public PriorityQueue<ResultRecord> localMin;
        public int k;
    }

    private int countDataPoints = 0;
    private ArrayList<String> ids = new ArrayList<String>();
    private HashMap<String, float[]> embeddings = new HashMap<String, float[]>();
    private float[][] flatEmbeddings;
    private String[] flatIds;
    private int threadCount;
    private int embeddingSize;
}
