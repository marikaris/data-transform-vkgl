package org.molgenis.core;

import static org.apache.camel.Exchange.FILE_NAME;
import static org.apache.camel.Exchange.HTTP_METHOD;
import static org.apache.camel.model.dataformat.JsonLibrary.Jackson;
import static org.apache.camel.util.toolbox.AggregationStrategies.groupedBody;
import static org.molgenis.mappers.AlissaMapper.ALISSA_HEADERS;
import static org.molgenis.mappers.LumcMapper.LUMC_HEADERS;
import static org.molgenis.mappers.RadboudMumcMapper.RADBOUD_HEADERS;

import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.dataformat.csv.CsvDataFormat;
import org.molgenis.mappers.AlissaVkglTableMapper;
import org.molgenis.mappers.GenericDataMapper;
import org.molgenis.mappers.LumcVkglTableMapper;
import org.molgenis.mappers.RadboudMumcVkglTableMapper;
import org.molgenis.utils.FileCreator;
import org.molgenis.validators.ReferenceSequenceValidator;
import org.molgenis.validators.UniquenessChecker;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.stereotype.Component;

@SpringBootApplication
@ComponentScan(basePackages = {"org.molgenis"})
@Component
public class MySpringBootRouter extends RouteBuilder {

  private static final int FILE_COMPLETION_TIMEOUT = 60000;
  private static final int DEFAULT_TIMEOUT = 1000;
  private static final int COMPLETION_SIZE = 1000;
  private static final String VCF_HEADERS = "hgvs_normalized_vkgl\tchrom\tpos\tref\talt\ttype\tsignificance";
  private static final String ERROR_HEADERS = "hgvs_normalized_vkgl\tcdna_patched\terror";
  private static final String VKGL_HEADERS = "id\tchromosome\tstart\tstop\tref\talt\tgene\tc_dna\thgvs_g\thgvs_c\ttranscript\tprotein\ttype\tlocation\texon\teffect\tclassification\tcomments\tis_legacy";

  private final ReferenceSequenceValidator refValidator;
  private final GenericDataMapper genericMapper;
  private final AlissaVkglTableMapper alissaTableMapper;
  private final RadboudMumcVkglTableMapper radboudMumcTableMapper;
  private final LumcVkglTableMapper lumcTableMapper;
  private final UniquenessChecker uniquenessChecker;

  public MySpringBootRouter(ReferenceSequenceValidator refValidator,
      GenericDataMapper genericMapper, AlissaVkglTableMapper alissaTableMapper,
      RadboudMumcVkglTableMapper radboudMumcTableMapper,
      LumcVkglTableMapper lumcTableMapper, UniquenessChecker uniquenessChecker) {
    this.refValidator = refValidator;
    this.genericMapper = genericMapper;
    this.alissaTableMapper = alissaTableMapper;
    this.radboudMumcTableMapper = radboudMumcTableMapper;
    this.lumcTableMapper = lumcTableMapper;
    this.uniquenessChecker = uniquenessChecker;
  }

  private Exchange mergeLists(Exchange variantExchange, Exchange responseExchange) {
    List<Map<String, Object>> variants = variantExchange.getIn().getBody(List.class);
    List<Map<String, Object>> validationResults = responseExchange.getIn().getBody(List.class);
    for (int i = 0; i < variants.size(); i++) {
      variants.get(i).putAll(validationResults.get(i));
    }
    return variantExchange;
  }

  private String[] getSplittedHeaders(String customHeaders, String defaultHeaders) {
    return (customHeaders + "\t" + defaultHeaders).split("\t");
  }

  @Override
  @SuppressWarnings("squid:S1192") // String literals should not be duplicated
  public void configure() {
    String resultFile = "file:result";

    from("direct:append-error")
        .to("file:result?fileName=vkgl_${file:name.noext}_error.txt&fileExist=Append");

    from("direct:write-alissa-error")
        .marshal(
            new CsvDataFormat()
                .setDelimiter('\t')
                .setHeader(getSplittedHeaders(ALISSA_HEADERS, ERROR_HEADERS))
                .setHeaderDisabled(true))
        .to("direct:append-error");

    from("direct:write-radboud-error")
        .marshal(
            new CsvDataFormat()
                .setDelimiter('\t')
                .setHeader(getSplittedHeaders(RADBOUD_HEADERS, ERROR_HEADERS))
                .setHeaderDisabled(true))
        .to("direct:append-error");

    from("direct:write-lumc-error")
        .marshal(
            new CsvDataFormat()
                .setDelimiter('\t')
                .setHeader(getSplittedHeaders(LUMC_HEADERS, ERROR_HEADERS))
                .setHeaderDisabled(true))
        .to("direct:append-error");

    from("direct:write-error")
        .recipientList(simple("direct:write-${header.labType}-error"));

    from("direct:marshal-alissa-result")
        .marshal(new CsvDataFormat().setDelimiter('\t')
            .setHeader(getSplittedHeaders(ALISSA_HEADERS, VCF_HEADERS)))
        .to(resultFile);

    from("direct:marshal-radboud-result")
        .marshal(new CsvDataFormat().setDelimiter('\t')
            .setHeader(getSplittedHeaders(RADBOUD_HEADERS, VCF_HEADERS)))
        .to(resultFile);

    from("direct:marshal-lumc-result")
        .marshal(new CsvDataFormat().setDelimiter('\t')
            .setHeader(getSplittedHeaders(LUMC_HEADERS, VCF_HEADERS)))
        .to(resultFile);

    from("direct:marshal-vkgl-result")
        .marshal(new CsvDataFormat().setDelimiter('\t')
            .setHeader((VKGL_HEADERS).split("\t"))
            .setHeaderDisabled(true))
        .to("file:result?fileName=vkgl_${file:name.noext}.tsv&fileExist=Append");

    from("direct:map-alissa-result")
        .split().body()
        .process().body(Map.class, alissaTableMapper::mapLine)
        .to("direct:marshal-vkgl-result");

    from("direct:map-lumc-result")
        .split().body()
        .process().body(Map.class, lumcTableMapper::mapLine)
        .to("direct:marshal-vkgl-result");

    from("direct:map-radboud-result")
        .split().body()
        .process().body(Map.class, radboudMumcTableMapper::mapLine)
        .to("direct:marshal-vkgl-result");

    from("direct:write-result")
        .aggregate(header(FILE_NAME))
        .strategy(groupedBody())
        .completionTimeout(DEFAULT_TIMEOUT)
        .to("log:done")
        .recipientList(simple(
            "direct:marshal-${header.labType}-result,direct:map-${header.labType}-result"));

    from("direct:check_unique")
        .aggregate(header(FILE_NAME))
        .strategy(groupedBody())
        .completionTimeout(FILE_COMPLETION_TIMEOUT)
        .process(uniquenessChecker::getUniqueVariants)
        .split().body()
        .choice()
        .when(simple("${body['error']} != null"))
        .to("log:error")
        .to("direct:write-error")
        .otherwise()
        .to("direct:write-result")
        .end()
        .end();

    from("direct:validate")
        .process()
        .body(Map.class, refValidator::validateOriginalRef)
        .to("direct:check_unique");

    from("direct:h2v")
        .to("log:httprequest")
        .transform()
        .jsonpath("$[*].hgvs_normalized_vkgl")
        .marshal()
        .json(Jackson)
        .setHeader(HTTP_METHOD, constant("POST"))
        .to("https4://variants.molgenis.org/h2v?keep_left_anchor=True&strict=True")
        .unmarshal()
        .json(Jackson)
        .to("log:httpresponse");

    from("direct:hgvs2vcf")
        .description("Validates the normalized gDNA.")
        .aggregate(header(FILE_NAME), groupedBody())
        .completionSize(COMPLETION_SIZE)
        .completionTimeout(DEFAULT_TIMEOUT)
        .enrich("direct:h2v", this::mergeLists)
        .split().body()
        .to("direct:validate");

    from("file:src/test/inbox/")
        .bean(FileCreator.class, "createOutputFile(\"result/vkgl_\"${file:name.noext}\".tsv\"," +
            VKGL_HEADERS + ")")
        .choice().when(simple("${header.CamelFileName} contains 'radboud'"))
        .unmarshal(new CsvDataFormat().setDelimiter('\t').setUseMaps(true).setHeader(
            RADBOUD_HEADERS.split("\t"))).otherwise()
        .unmarshal(new CsvDataFormat().setDelimiter('\t').setUseMaps(true))
        .end()
        .split().body()
        .process().exchange(genericMapper::mapData)
        .to("direct:hgvs2vcf");
  }
}
