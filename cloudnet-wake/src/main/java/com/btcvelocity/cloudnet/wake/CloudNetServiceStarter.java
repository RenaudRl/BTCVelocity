/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.btcvelocity.cloudnet.wake;

import eu.cloudnetservice.driver.provider.CloudServiceFactory;
import eu.cloudnetservice.driver.provider.CloudServiceProvider;
import eu.cloudnetservice.driver.provider.ServiceTaskProvider;
import eu.cloudnetservice.driver.service.ServiceConfiguration;
import eu.cloudnetservice.driver.service.ServiceCreateResult;
import eu.cloudnetservice.driver.service.ServiceInfoSnapshot;
import eu.cloudnetservice.driver.service.ServiceLifeCycle;
import eu.cloudnetservice.driver.service.ServiceTask;
import java.util.Collection;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.slf4j.Logger;

/**
 * Starts a task through the CloudNet driver the wrapper injects. The node deduplicates nothing
 * ({@code createCloudServiceAsync} always creates a new instance), so this reuses before it creates;
 * the "one wake per task" rule across simultaneous requests belongs to {@link WakeCoordinator}.
 *
 * <p>Every call is asynchronous: the driver proxies are RPCs to the node, and nothing here may
 * run on a Netty or game thread.
 */
final class CloudNetServiceStarter implements ServiceStarter {

  private final Logger logger;
  private final CloudServiceProvider services;
  private final CloudServiceFactory factory;
  private final ServiceTaskProvider tasks;
  private final Function<ServiceTask, ServiceConfiguration> configurations;

  CloudNetServiceStarter(Logger logger, CloudServiceProvider services, CloudServiceFactory factory,
                         ServiceTaskProvider tasks,
                         Function<ServiceTask, ServiceConfiguration> configurations) {
    this.logger = logger;
    this.services = services;
    this.factory = factory;
    this.tasks = tasks;
    this.configurations = configurations;
  }

  @Override
  public CompletableFuture<Outcome> ensureStarted(String task) {
    try {
      return this.services.servicesByTaskAsync(task)
          .thenCompose(instances -> this.startOrCreate(task, instances))
          .exceptionally(error -> this.failed(task, error));
    } catch (RuntimeException error) {
      return CompletableFuture.completedFuture(this.failed(task, error));
    }
  }

  private CompletableFuture<Outcome> startOrCreate(String task,
                                                   Collection<ServiceInfoSnapshot> instances) {
    // RUNNING but not registered: it is starting, a second start would only race it.
    if (instances.stream().anyMatch(i -> i.lifeCycle() == ServiceLifeCycle.RUNNING)) {
      return CompletableFuture.completedFuture(Outcome.ALREADY_STARTING);
    }
    return instances.stream()
        .filter(i -> i.lifeCycle() == ServiceLifeCycle.PREPARED)
        .min(Comparator.comparing(ServiceInfoSnapshot::name))
        .map(i -> this.start(i.serviceId().uniqueId()))
        .orElseGet(() -> this.create(task));
  }

  private CompletableFuture<Outcome> create(String task) {
    return this.tasks.serviceTaskAsync(task).thenCompose(serviceTask -> {
      if (serviceTask == null) {
        return CompletableFuture.completedFuture(Outcome.UNKNOWN_TASK);
      }
      return this.factory.createCloudServiceAsync(this.configurations.apply(serviceTask))
          .thenCompose(result -> this.afterCreate(task, result));
    });
  }

  private CompletableFuture<Outcome> afterCreate(String task, ServiceCreateResult result) {
    if (result == null || result.state() == ServiceCreateResult.State.FAILED) {
      this.logger.warn("CloudNet could not create a service for task {}.", task);
      return CompletableFuture.completedFuture(Outcome.FAILED);
    }
    if (result.state() == ServiceCreateResult.State.DEFERRED) {
      this.logger.warn("CloudNet deferred the creation of a service for task {}.", task);
      return CompletableFuture.completedFuture(Outcome.DEFERRED);
    }
    return this.start(result.serviceInfo().serviceId().uniqueId());
  }

  private CompletableFuture<Outcome> start(UUID serviceId) {
    return this.services.serviceProvider(serviceId).startAsync().thenApply(done -> Outcome.STARTED);
  }

  private Outcome failed(String task, Throwable error) {
    this.logger.warn("Could not start a service for task {}.", task, error);
    return Outcome.FAILED;
  }
}
