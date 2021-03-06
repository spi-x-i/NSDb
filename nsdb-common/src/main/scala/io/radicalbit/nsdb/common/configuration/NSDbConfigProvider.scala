/*
 * Copyright 2018-2020 Radicalbit S.r.l.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.radicalbit.nsdb.common.configuration

import com.typesafe.config.{Config, ConfigFactory}
import io.radicalbit.nsdb.common.configuration.NSDbConfig.HighLevel._
import io.radicalbit.nsdb.common.configuration.NSDbConfig.LowLevel._

/**
  * Manages the build of NSDb configuration.
  * NSDb config will be used for the actor system creation but also to retrieve user configuration properties (e.g. storage folders)
  * The overall config is built from a low level template (containing all the akka configs), that is merged with a high level config file
  * that contains all the user defined keys.
  */
trait NSDbConfigProvider {

  /**
    * @return the user defined configuration.
    */
  def userDefinedConfig: Config

  /**
    * @return the low level akka configuration template.
    */
  def lowLevelTemplateConfig: Config

  /**
    * Merges a list of [[Config]]
    * @param configs the input configurations.
    * @return the merged configuration.
    */
  protected def mergeConf(configs: Config*): Config =
    configs
      .fold(ConfigFactory.empty()) { (acc, e) =>
        acc.withFallback(e)
      }

  /**
    * Populates the low level template with the configuration keys provided in the user level one.
    * @param userDefinedConfig The user defined configurations.
    * @param lowLevelTemplateConfig The low level template configurations.
    * @return The final configuration.
    */
  private def populateTemplate(userDefinedConfig: Config, lowLevelTemplateConfig: Config): Config = {
    var populatedConfigs = lowLevelTemplateConfig
    if (populatedConfigs.hasPath(AkkaArteryHostName))
      populatedConfigs = populatedConfigs.withValue(AkkaArteryHostName, userDefinedConfig.getValue(NSDbNodeHostName))
    if (populatedConfigs.hasPath(AkkaArteryPort))
      populatedConfigs = populatedConfigs.withValue(AkkaArteryPort, userDefinedConfig.getValue(NSDbNodePort))
    if (populatedConfigs.hasPath(AkkaManagementContactPointNr))
      populatedConfigs =
        populatedConfigs.withValue(AkkaManagementContactPointNr, userDefinedConfig.getValue(NSDbClusterContactPointNr))
    if (populatedConfigs.hasPath(AkkaDiscoveryNSDbEndpoints))
      populatedConfigs =
        populatedConfigs.withValue(AkkaDiscoveryNSDbEndpoints, userDefinedConfig.getValue(NSDbClusterEndpoints))

    populatedConfigs
      .withValue(AkkaDDPersistenceDir, userDefinedConfig.getValue(NSDBMetadataPath))
      .resolve()
  }

  /**
    * The final NSDb configuration.
    */
  final lazy val config: Config = populateTemplate(userDefinedConfig, lowLevelTemplateConfig)

}
