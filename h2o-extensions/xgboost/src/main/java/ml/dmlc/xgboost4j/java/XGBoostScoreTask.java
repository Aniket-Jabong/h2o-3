package ml.dmlc.xgboost4j.java;

import hex.*;
import hex.tree.xgboost.*;
import hex.tree.xgboost.XGBoost;
import water.*;
import water.fvec.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static water.fvec.Vec.T_CAT;
import static water.fvec.Vec.T_NUM;

public class XGBoostScoreTask extends MRTask<XGBoostScoreTask> {

    private final XGBoostModelInfo _sharedmodel;
    private final XGBoostOutput _output;
    private final XGBoostModel.XGBoostParameters _parms;
    private final BoosterParms _boosterParms;
    private final boolean _computeMetrics;
    private final int _weightsChunkId;
    private final Model _model;
    private final double _threshold;

    private ModelMetrics.MetricBuilder _metricBuilder;
    private byte[] rawBooster;

    public static class XGBoostScoreTaskResult {
        public Frame preds;
        public ModelMetrics mm;
    }

    public static XGBoostScoreTaskResult runScoreTask(XGBoostModelInfo sharedmodel,
                                                      XGBoostOutput output,
                                                      XGBoostModel.XGBoostParameters parms,
                                                      Booster booster,
                                                      Key<Frame> destinationKey,
                                                      Frame data,
                                                      Frame originalData,
                                                      boolean computeMetrics,
                                                      Model m) {
        BoosterParms boosterParms = XGBoostModel.createParams(parms, output.nclasses());
        XGBoostScoreTask task = new XGBoostScoreTask(sharedmodel,
                output,
                parms,
                booster,
                boosterParms,
                computeMetrics,
                data.find(parms._weights_column),
                m).doAll(outputTypes(output), data);

        final String[] names = Model.makeScoringNames(output);
        final Frame preds = task.outputFrame(destinationKey, names, makeDomains(output, names));

        XGBoostScoreTaskResult res = new XGBoostScoreTaskResult();

        if (output.nclasses() == 1) {
            Vec pred = preds.vec(0);
            if (computeMetrics) {
                res.mm = task._metricBuilder.makeModelMetrics(m, originalData, data, new Frame(pred));
            }
        } else if (output.nclasses() == 2) {
            Vec p1 = preds.vec(2);
            if (computeMetrics) {
                res.mm = task._metricBuilder.makeModelMetrics(m, originalData, data, new Frame(p1));
            }
        } else {
            if (computeMetrics) {
                Frame pp = new Frame(preds);
                pp.remove(0);
                Scope.enter();
                res.mm = task._metricBuilder.makeModelMetrics(m, originalData, data, pp);
                Scope.exit();
            }
        }
        res.preds = preds;
        assert "predict".equals(preds.name(0));

        return res;
    }


    private static byte[] outputTypes(XGBoostOutput output) {
        // Last output is the response, which eventually will be removed before returning the preds Frame but is needed to build metrics
        if(output.nclasses() == 1) {
            return new byte[]{T_NUM};
        } else if(output.nclasses() == 2) {
            return new byte[]{T_CAT, T_NUM, T_NUM};
        } else{
            byte[] types = new byte[output.nclasses() + 1];
            Arrays.fill(types, T_NUM);
            return types;
        }
    }

    private static String[][] makeDomains(XGBoostOutput output, String[] names) {
        if(output.nclasses() == 1) {
            return null;
        } else if(output.nclasses() == 2) {
            String[][] domains = new String[3][];
            domains[0] = new String[]{"N", "Y"};
            return domains;
        } else{
            String[][] domains = new String[names.length][];
            domains[0] = output.classNames();
            return domains;
        }
    }

    private XGBoostScoreTask(final XGBoostModelInfo sharedmodel,
                             final XGBoostOutput output,
                             final XGBoostModel.XGBoostParameters parms,
                             final Booster booster,
                             final BoosterParms boosterParms,
                             final boolean computeMetrics,
                             final int weightsChunkId,
                             final Model model) {
        _sharedmodel = sharedmodel;
        _output = output;
        _parms = parms;
        _boosterParms = boosterParms;
        this.rawBooster = XGBoost.getRawArray(booster);
        _computeMetrics = computeMetrics;
        _weightsChunkId = weightsChunkId;
        _model = model;
        _threshold = Model.defaultThreshold(_output);
    }

    /**
     * Constructs a MetricBuilder for this XGBoostScoreTask based on parameters of response variable
     *
     * @param responseClassesNum Number of classes found in response variable
     * @param responseDomain     Specific domains in response variable
     * @return An instance of {@link hex.ModelMetrics.MetricBuilder} corresponding to given response variable type
     */
    private ModelMetrics.MetricBuilder createMetricsBuilder(final int responseClassesNum, final String[] responseDomain) {
        switch (responseClassesNum) {
            case 1:
                return new ModelMetricsRegression.MetricBuilderRegression();
            case 2:
                return new ModelMetricsBinomial.MetricBuilderBinomial(responseDomain);
            default:
                return new ModelMetricsMultinomial.MetricBuilderMultinomial(responseClassesNum, responseDomain);
        }
    }


    @Override
    public void map(Chunk[] cs, NewChunk[] ncs) {
        DMatrix data = null;
        Booster booster = null;
        _metricBuilder = _computeMetrics ? createMetricsBuilder(_output.nclasses(), _output.classNames()) : null;
        try {
            Map<String, String> rabitEnv = new HashMap<>();
            // Rabit has to be initialized as parts of booster.predict() are using Rabit
            // This might be fixed in future versions of XGBoost
            Rabit.init(rabitEnv);

            data = XGBoostUtils.convertChunksToDMatrix(
                    _sharedmodel._dataInfoKey,
                    cs,
                    _fr.find(_parms._response_column),
                    -1, // not used for preds
                    _fr.find(_parms._fold_column),
                    _output._sparse);

            // No local chunks for this frame
            if (data.rowNum() == 0) {
                return;
            }

            try {
                booster = Booster.loadModel(new ByteArrayInputStream(rawBooster));
                booster.setParams(_boosterParms.get());
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load the booster.", e);
            }
            final float[][] preds = booster.predict(data);

            float[] labels = data.getLabel();


            if (_output.nclasses() == 1) {
                double[] currentPred = new double[1];
                float[] yact = new float[1];
                for (int j = 0; j < preds.length; ++j) {
                    currentPred[0] = preds[j][0];
                    if (_computeMetrics) {
                        yact[0] = labels[j];
                        double weight = _weightsChunkId != -1 ? cs[_weightsChunkId].atd(j) : 1; // If there is no chunk with weights, the weight is considered to be 1
                        _metricBuilder.perRow(currentPred, yact, weight, 0, _model);
                    }
                }
                for (int i = 0; i < cs[0]._len; ++i) {
                    ncs[0].addNum(preds[i][0]);
                }
            } else if (_output.nclasses() == 2) {
                double[] row = new double[3];
                float[] yact = new float[1];
                for (int i = 0; i < cs[0]._len; ++i) {
                    final double p = preds[i][0];
                    row[1] = 1 - p;
                    row[2] = p;
                    row[0] = hex.genmodel.GenModel.getPrediction(row, _output._priorClassDist, null, _threshold);

                    ncs[0].addNum(row[0]);
                    ncs[1].addNum(row[1]);
                    ncs[2].addNum(row[2]);

                    if (_computeMetrics) {
                        double weight = _weightsChunkId != -1 ? cs[_weightsChunkId].atd(i) : 1; // If there is no chunk with weights, the weight is considered to be 1
                        yact[0] = labels[i];
                        _metricBuilder.perRow(row, yact, weight, 0, _model);
                    }
                }
            } else {
                float[] yact = new float[1];
                double[] row = MemoryManager.malloc8d(ncs.length);
                for (int i = 0; i < cs[0]._len; ++i) {
                    for (int j = 1; j < row.length; ++j) {
                        double val = preds[i][j - 1];
                        ncs[j].addNum(val);
                        row[j] = val;
                    }
                    ncs[0].addNum(hex.genmodel.GenModel.getPrediction(row, _output._priorClassDist, null, _threshold));
                    if (_computeMetrics) {
                        yact[0] = labels[i];
                        double weight = _weightsChunkId != -1 ? cs[_weightsChunkId].atd(i) : 1; // If there is no chunk with weights, the weight is considered to be 1
                        _metricBuilder.perRow(row, yact, weight, 0, _model);
                    }
                }
            }
        } catch (XGBoostError xgBoostError) {
            throw new IllegalStateException("Failed to score with XGBoost.", xgBoostError);
        } finally {
            BoosterHelper.dispose(booster, data);
            try {
                Rabit.shutdown();
            } catch (XGBoostError xgBoostError) {
                throw new IllegalStateException("Failed Rabit shutdown. A hanging RabitTracker task might be present on the driver node.", xgBoostError);
            }
        }
    }

    @Override
    public void reduce(XGBoostScoreTask mrt) {
        super.reduce(mrt);
        if (_computeMetrics) {
            _metricBuilder.reduce(mrt._metricBuilder);
        }
    }


}
