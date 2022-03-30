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

import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.webapp.WebAppContext

/**
 * Server running the PrimeFactorServlet.
 *
 * @author
 *   Chris de Vreeze
 */
object PrimeFactorServer:

  @main
  def main(host: String, port: Int): Unit =
    val server = new Server()
    val connector = new ServerConnector(server)
    connector.setHost(host)
    connector.setPort(port)
    server.setConnectors(Array(connector))

    val webAppContext = new WebAppContext()
    server.setHandler(webAppContext)

    webAppContext.setContextPath("/")
    // See https://happycoding.io/tutorials/java-server/embedded-jetty
    // and https://stackoverflow.com/questions/25674206/embedded-jetty-does-not-find-annotated-servlet
    webAppContext.setAttribute("org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern", "./*")

    server.start()
    println(s"Server started on port $port")
    server.join()
  end main

end PrimeFactorServer
