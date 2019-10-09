package org.molgenis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.apache.camel.Exchange;

class UniquenessChecker {

  private static HashMap<String, HashMap> uniqueVariants = new HashMap<>();

  List<HashMap> getUniqueVariantsList(List<HashMap> body) {
    List<HashMap> listOfUniqueVariants = new ArrayList<>();
    for (HashMap variant : body) {
      if (!variant.containsKey("error")) {
        String id = variant.get("chrom") + Integer.toString((Integer) variant.get("pos")) +
            variant.get("ref") + variant.get("alt") + variant.get("gene");
        if (uniqueVariants.containsKey(id)) {
          HashMap uniqueVariant = uniqueVariants.get(id);
          if (uniqueVariant.containsKey("error")) {
            String error = (String) uniqueVariant.get("error");
            uniqueVariant.put("error", error + "," + variant.get("hgvs_normalized_vkgl"));
          } else {
            uniqueVariant.put("error",
                "Variant duplicated: " + uniqueVariant.get("hgvs_normalized_vkgl") + "," + variant
                    .get("hgvs_normalized_vkgl"));
          }
        } else {
          uniqueVariants.put(id, variant);
        }
      } else {
        listOfUniqueVariants.add(variant);
      }
    }
    listOfUniqueVariants.addAll(uniqueVariants.values());
    return listOfUniqueVariants;
  }

  void getUniqueVariants(Exchange exchange) {
    List<HashMap> body = (List<HashMap>) exchange.getIn().getBody(List.class);
    List<HashMap> listOfUniqueVariants = getUniqueVariantsList(body);
    exchange.getIn().setBody(listOfUniqueVariants);
  }
}