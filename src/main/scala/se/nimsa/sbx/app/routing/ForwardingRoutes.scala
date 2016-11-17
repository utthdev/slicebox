/*
 * Copyright 2016 Lars Edenbrandt
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

package se.nimsa.sbx.app.routing

import akka.pattern.ask
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import se.nimsa.sbx.app.SliceboxServices
import se.nimsa.sbx.user.UserProtocol._
import se.nimsa.sbx.forwarding.ForwardingProtocol._

trait ForwardingRoutes { this: SliceboxServices =>

  def forwardingRoutes(apiUser: ApiUser): Route =
    pathPrefix("forwarding") {
      pathPrefix("rules") {
        pathEndOrSingleSlash {
          get {
            parameters(
              'startindex.as(nonNegativeFromStringUnmarshaller) ? 0,
              'count.as(nonNegativeFromStringUnmarshaller) ? 20) { (startIndex, count) =>
              onSuccess(forwardingService.ask(GetForwardingRules(startIndex, count))) {
                case ForwardingRules(forwardingRules) =>
                  complete(forwardingRules)
              }
            }
          } ~ post {
            authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
              entity(as[ForwardingRule]) { forwardingRule =>
                onSuccess(forwardingService.ask(AddForwardingRule(forwardingRule))) {
                  case ForwardingRuleAdded(addedForwardingRule) =>
                    complete((Created, addedForwardingRule))
                }
              }
            }
          }
        } ~ pathPrefix(LongNumber) { forwardingRuleId =>
          pathEndOrSingleSlash {
            delete {
              authorize(apiUser.hasPermission(UserRole.ADMINISTRATOR)) {
                onSuccess(forwardingService.ask(RemoveForwardingRule(forwardingRuleId))) {
                  case ForwardingRuleRemoved(_) =>
                    complete(NoContent)
                }
              }
            }
          }
        }
      }
    }

}
