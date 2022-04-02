/*
 * Copyright 2022-2022 Chris de Vreeze
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.cdevreeze.tryzio.http.legacy

import java.io.File

import org.apache.catalina.LifecycleException
import org.apache.catalina.connector.Connector
import org.apache.catalina.startup.Tomcat

/**
 * Embedded Tomcat server running the PrimeFactorServlet.
 *
 * @author
 *   Chris de Vreeze
 */
object PrimeFactorServer:

  private val firstPort = 8080

  // @main
  @throws[LifecycleException]
  def main(args: Array[String]): Unit =
    val port = args.ensuring(_.lengthIs == 1).apply(0).toInt
    val tomcat: Tomcat = new Tomcat()
    tomcat.setBaseDir("temp")
    tomcat.setPort(port)
    // Needed. See https://stackoverflow.com/questions/71383171/simple-embedded-tomcat-10-example.
    val connector1 = tomcat.getConnector
    connector1.setPort(firstPort)
    val connector2 = new Connector()
    connector2.setPort(port)

    val contextPath = ""
    val docBase = new File(".").getAbsolutePath

    val context = tomcat.addContext(contextPath, docBase)

    val servletName = "primeFactorServlet"
    val urlPattern = "/primeFactors/*"

    tomcat.addServlet(contextPath, servletName, new PrimeFactorServlet()) // No need to "enable async".
    context.addServletMappingDecoded(urlPattern, servletName)

    tomcat.start()
    tomcat.getService.addConnector(connector1)
    tomcat.getService.addConnector(connector2)
    println(s"Tomcat started, listening at ports $firstPort and $port")
    tomcat.getServer.await()
  end main

end PrimeFactorServer
