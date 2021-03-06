/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.metron.management;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.jakewharton.fliptables.FlipTable;
import org.apache.log4j.Logger;
import org.apache.metron.common.configuration.ConfigurationType;
import org.apache.metron.common.configuration.FieldTransformer;
import org.apache.metron.common.configuration.SensorParserConfig;
import org.apache.metron.common.dsl.*;
import org.apache.metron.common.field.transformation.FieldTransformation;
import org.apache.metron.common.field.transformation.FieldTransformations;
import org.apache.metron.common.stellar.shell.StellarExecutor;
import org.apache.metron.common.utils.JSONUtils;
import org.jboss.aesh.console.Console;

import java.util.*;

import static org.apache.metron.common.configuration.ConfigurationType.PARSER;

public class ParserConfigFunctions {
  private static final Logger LOG = Logger.getLogger(ParserConfigFunctions.class);

  private static void pruneEmptyStellarTransformers(SensorParserConfig config) {
    List<FieldTransformer> toRemove = new ArrayList<>();
    List<FieldTransformer> fieldTransformations = config.getFieldTransformations();
    for(FieldTransformer transformer : fieldTransformations) {
      if(transformer.getFieldTransformation().getClass().getName()
              .equals(FieldTransformations.STELLAR.getMappingClass().getName())
        && transformer.getConfig().isEmpty()
        ) {
          toRemove.add(transformer);
      }
    }
    for(FieldTransformer t : toRemove) {
      fieldTransformations.remove(t);
    }
  }
  private static FieldTransformer getStellarTransformer(SensorParserConfig config) {
    List<FieldTransformer> fieldTransformations = config.getFieldTransformations();
    FieldTransformer stellarTransformer = null;
    for(FieldTransformer transformer : fieldTransformations) {
      if(transformer.getFieldTransformation().getClass().getName()
              .equals(FieldTransformations.STELLAR.getMappingClass().getName())) {
        stellarTransformer = transformer;
      }
    }
    if(stellarTransformer == null) {
      stellarTransformer = new FieldTransformer();
      stellarTransformer.setConfig(new LinkedHashMap<>());
      stellarTransformer.setTransformation(FieldTransformations.STELLAR.toString());
      fieldTransformations.add(stellarTransformer);
    }
    return stellarTransformer;
  }

  @Stellar(
           namespace = "PARSER_STELLAR_TRANSFORM"
          ,name = "PRINT"
          ,description = "Retrieve stellar field transformations."
          ,params = {"sensorConfig - Sensor config to add transformation to."
                    }
          ,returns = "The String representation of the transformations"
          )
  public static class PrintStellarTransformation implements StellarFunction {

    @Override
    public Object apply(List<Object> args, Context context) throws ParseException {
      String config = (String) args.get(0);
      if(config == null) {
        return null;
      }
      SensorParserConfig configObj = (SensorParserConfig) PARSER.deserialize(config);
      FieldTransformer stellarTransformer = getStellarTransformer(configObj);
      String[] headers = new String[] { "Field", "Transformation"};
      String[][] data = new String[stellarTransformer.getConfig().size()][2];
      int i = 0;
      for(Map.Entry<String, Object> kv : stellarTransformer.getConfig().entrySet()) {
        data[i++] = new String[] {kv.getKey(), kv.getValue().toString()};
      }
      return FlipTable.of(headers, data);
    }

    @Override
    public void initialize(Context context) {

    }

    @Override
    public boolean isInitialized() {
      return true;
    }
  }

  @Stellar(
           namespace = "PARSER_STELLAR_TRANSFORM"
          ,name = "REMOVE"
          ,description = "Remove stellar field transformation."
          ,params = {"sensorConfig - Sensor config to add transformation to."
                    ,"stellarTransforms - A list of stellar transforms to remove"
                    }
          ,returns = "The String representation of the config in zookeeper"
          )
  public static class RemoveStellarTransformation implements StellarFunction{

    @Override
    public Object apply(List<Object> args, Context context) throws ParseException {
      String config = (String) args.get(0);
      if(config == null) {
        return null;
      }
      SensorParserConfig configObj = (SensorParserConfig) PARSER.deserialize(config);
      FieldTransformer stellarTransformer = getStellarTransformer(configObj);
      List<String> removals = (List<String>)args.get(1);
      if(removals == null || removals.isEmpty()) {
        return config;
      }
      for(String removal : removals) {
        stellarTransformer.getConfig().remove(removal);
      }
      List<String> output = new ArrayList<>();
      output.addAll(stellarTransformer.getConfig().keySet());
      stellarTransformer.setOutput(output);
      pruneEmptyStellarTransformers(configObj);
      try {
        return JSONUtils.INSTANCE.toJSON(configObj, true);
      } catch (JsonProcessingException e) {
        LOG.error("Unable to convert object to JSON: " + configObj, e);
        return config;
      }
    }

    @Override
    public void initialize(Context context) {

    }

    @Override
    public boolean isInitialized() {
      return true;
    }
  }

  @Stellar(
           namespace = "PARSER_STELLAR_TRANSFORM"
          ,name = "ADD"
          ,description = "Add stellar field transformation."
          ,params = {"sensorConfig - Sensor config to add transformation to."
                    ,"stellarTransforms - A Map associating fields to stellar expressions"
                    }
          ,returns = "The String representation of the config in zookeeper"
          )
  public static class AddStellarTransformation implements StellarFunction{

    @Override
    public Object apply(List<Object> args, Context context) throws ParseException {
      String config = (String) args.get(0);
      if(config == null) {
        return null;
      }
      SensorParserConfig configObj = (SensorParserConfig) PARSER.deserialize(config);
      FieldTransformer stellarTransformer = getStellarTransformer(configObj);
      Map<String, String> additionalTransforms = (Map<String, String>) args.get(1);
      if(additionalTransforms == null || additionalTransforms.isEmpty()) {
        return config;
      }
      for(Map.Entry<String, String> kv : additionalTransforms.entrySet()) {
        stellarTransformer.getConfig().put(kv.getKey(), kv.getValue());

      }
      List<String> output = new ArrayList<>();

      output.addAll(stellarTransformer.getConfig().keySet());
      stellarTransformer.setOutput(output);

      try {
        return JSONUtils.INSTANCE.toJSON(configObj, true);
      } catch (JsonProcessingException e) {
        LOG.error("Unable to convert object to JSON: " + configObj, e);
        return config;
      }
    }

    @Override
    public void initialize(Context context) {

    }

    @Override
    public boolean isInitialized() {
      return true;
    }
  }
}
