package chapter07.xgb;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.primitives.Doubles;

import chapter07.BeanToJoinery;
import chapter07.RankedPage;
import chapter07.text.CountVectorizer;
import chapter07.text.MatrixUtils;
import chapter07.text.TruncatedSVD;
import joinery.DataFrame;
import smile.data.SparseDataset;

public class TextFeatureExtractor implements Serializable {

    private static final Logger LOGGER = LoggerFactory.getLogger(TextFeatureExtractor.class);

    private CountVectorizer allVectorizer;
    private CountVectorizer titleVectorizer;
    private CountVectorizer headerVectorizer;
    private TruncatedSVD svdAll;
    private TruncatedSVD svdTitle;

    public TextFeatureExtractor fit(List<RankedPage> data) throws Exception {
        Stopwatch stopwatch;

        stopwatch = Stopwatch.createStarted();
        LOGGER.debug("tokenizing all body texts... ");
        List<List<String>> bodyText = data.parallelStream()
                .map(p -> p.getBody())
                .collect(Collectors.toList());
        LOGGER.debug("took " + stopwatch.stop());

        stopwatch = Stopwatch.createStarted();
        LOGGER.debug("tokenizing all titles... ");
        List<List<String>> titles = data.parallelStream()
                .map(p -> p.getTitle())
                .collect(Collectors.toList());
        LOGGER.debug("took " + stopwatch.stop());

        stopwatch = Stopwatch.createStarted();
        LOGGER.debug("tokenizing all headers... ");
        List<List<String>> headers = data.parallelStream()
                .map(p -> new ArrayList<>(p.getHeaders().values()))
                .collect(Collectors.toList());
        LOGGER.debug("took " + stopwatch.stop());

        List<List<String>> all = new ArrayList<>(bodyText);
        all.addAll(titles);


        stopwatch = Stopwatch.createStarted();
        LOGGER.debug("vectorizing all texts... ");
        allVectorizer = CountVectorizer.create()
                .withMinimalDocumentFrequency(5)
                .withIdfTransformation()
                .withL2Normalization()
                .withSublinearTfTransformation()
                .fit(all);
        LOGGER.debug("took " + stopwatch.stop());

        stopwatch = Stopwatch.createStarted();
        LOGGER.debug("SVD'ing all texts... ");
        svdAll = new TruncatedSVD(150, true);
        svdAll.fit(allVectorizer.transfrom(all));
        LOGGER.debug("took " + stopwatch.stop());


        stopwatch = Stopwatch.createStarted();
        LOGGER.debug("vectorizing all titles... ");
        titleVectorizer = CountVectorizer.create()
                .withMinimalDocumentFrequency(3)
                .withIdfTransformation()
                .withL2Normalization()
                .fit(titles);
        LOGGER.debug("took " + stopwatch.stop());

        stopwatch = Stopwatch.createStarted();
        LOGGER.debug("SVD'ing all titles... ");
        svdTitle = new TruncatedSVD(50, true);
        svdTitle.fit(titleVectorizer.transfrom(titles));
        LOGGER.debug("took " + stopwatch.stop());


        stopwatch = Stopwatch.createStarted();
        LOGGER.debug("vectorizing all headers... ");
        headerVectorizer = CountVectorizer.create()
                .withMinimalDocumentFrequency(3)
                .withIdfTransformation()
                .withL2Normalization()
                .fit(headers);
        LOGGER.debug("took " + stopwatch.stop());

        return this;
    }

    public DataFrame<Double> transform(List<RankedPage> data) throws Exception {
        Stopwatch stopwatch;

        LOGGER.debug("tranforming the input...");

        DataFrame<Object> df = BeanToJoinery.convert(data, RankedPage.class);
        df = df.retain("url", "body", "query", "title", "headers");

        DataFrame<Double> result = new DataFrame<>(df.index(), Collections.emptySet());

        LOGGER.debug("tokenizing queries...");
        stopwatch = Stopwatch.createStarted();

        List<List<String>> query = castToListListString(df.col("query"));
        SparseDataset queryVectors = allVectorizer.transfrom(query);
        LOGGER.debug("took {}", stopwatch.stop());

        LOGGER.debug("tokenizing body...");
        stopwatch = Stopwatch.createStarted();
        List<List<String>> body = castToListListString(df.col("body"));
        SparseDataset bodyVectors = allVectorizer.transfrom(body);
        LOGGER.debug("took {}", stopwatch.stop());

        LOGGER.debug("computing similarity between query and raw body vectors...");
        stopwatch = Stopwatch.createStarted();
        double[] queryBodySimilarity = MatrixUtils.rowWiseSparseDot(queryVectors, bodyVectors);
        result.add("queryBodySimilarity", Doubles.asList(queryBodySimilarity));
        LOGGER.debug("took {}", stopwatch.stop());

        LOGGER.debug("computing similarity between query and body in the LSI space...");
        stopwatch = Stopwatch.createStarted();
        double[][] queryLsi = svdAll.transform(queryVectors);
        double[][] bodyLsi = svdAll.transform(bodyVectors);

        double[] queryBodyLsi = MatrixUtils.rowWiseDot(queryLsi, bodyLsi);
        result.add("queryBodyLsi", Doubles.asList(queryBodyLsi));
        LOGGER.debug("took {}", stopwatch.stop());

        LOGGER.debug("tokenizing titles...");
        stopwatch = Stopwatch.createStarted();
        List<List<String>> titles = castToListListString(df.col("title"));

        SparseDataset titleVectors = titleVectorizer.transfrom(titles);
        queryVectors = titleVectorizer.transfrom(query);
        LOGGER.debug("took {}", stopwatch.stop());

        LOGGER.debug("computing similarity between query and raw title vectors...");
        stopwatch = Stopwatch.createStarted();
        double[] queryTitleSimilarity = MatrixUtils.rowWiseSparseDot(queryVectors, titleVectors);
        result.add("queryTitleSimilarity", Doubles.asList(queryTitleSimilarity));
        LOGGER.debug("took {}", stopwatch.stop());

        LOGGER.debug("computing similarity between query and title in the LSI space...");
        stopwatch = Stopwatch.createStarted();
        double[][] titleLsi = svdTitle.transform(titleVectors);
        queryLsi = svdTitle.transform(queryVectors);
        double[] queryTitleLsi = MatrixUtils.rowWiseDot(queryLsi, titleLsi);
        result.add("queryTitleLsi", Doubles.asList(queryTitleLsi));
        LOGGER.debug("took {}", stopwatch.stop());

        LOGGER.debug("tokenizing headers...");
        stopwatch = Stopwatch.createStarted();
        @SuppressWarnings("unchecked")
        List<List<String>> headers = df.col("headers").stream()
                .map(h -> (ArrayListMultimap<String, String>) h)
                .map(h -> new ArrayList<>(h.values()))
                .collect(Collectors.toList());

        SparseDataset headerVectors = headerVectorizer.transfrom(headers);
        queryVectors = headerVectorizer.transfrom(query);
        LOGGER.debug("took {}", stopwatch.stop());

        LOGGER.debug("computing similarity between query and raw headers vectors...");
        stopwatch = Stopwatch.createStarted();
        double[] queryHeaderSimilarity = MatrixUtils.rowWiseSparseDot(queryVectors, headerVectors);
        result.add("queryHeaderSimilarity", Doubles.asList(queryHeaderSimilarity));
        LOGGER.debug("took {}", stopwatch.stop());

        LOGGER.debug("computing individual headers featurse...");
        stopwatch = Stopwatch.createStarted();

        String[] headerTags = { "h1", "h2", "h3" };
        for (String headerTag : headerTags) {
            @SuppressWarnings("unchecked")
            List<List<String>> header = df.col("headers").stream()
                    .map(h -> (ArrayListMultimap<String, String>) h)
                    .map(h -> h.get(headerTag))
                    .collect(Collectors.toList());

            headerVectors = headerVectorizer.transfrom(header);

            queryHeaderSimilarity = MatrixUtils.rowWiseSparseDot(queryVectors, headerVectors);
            result.add("queryHeaderSimilarity_" + headerTag, Doubles.asList(queryHeaderSimilarity));
        }
        LOGGER.debug("took {}", stopwatch.stop());

        return result;
    }

    private static List<List<String>> castToListListString(List<Object> input) {
        List<?> untyped = input;
        @SuppressWarnings("unchecked")
        List<List<String>> retyped = (List<List<String>>) untyped;
        return retyped;
    }

}
