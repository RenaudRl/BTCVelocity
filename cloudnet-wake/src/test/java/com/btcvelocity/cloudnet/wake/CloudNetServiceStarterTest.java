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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.btcvelocity.cloudnet.wake.ServiceStarter.Outcome;
import eu.cloudnetservice.driver.provider.CloudServiceFactory;
import eu.cloudnetservice.driver.provider.CloudServiceProvider;
import eu.cloudnetservice.driver.provider.ServiceTaskProvider;
import eu.cloudnetservice.driver.provider.SpecificCloudServiceProvider;
import eu.cloudnetservice.driver.service.ServiceConfiguration;
import eu.cloudnetservice.driver.service.ServiceCreateResult;
import eu.cloudnetservice.driver.service.ServiceId;
import eu.cloudnetservice.driver.service.ServiceInfoSnapshot;
import eu.cloudnetservice.driver.service.ServiceLifeCycle;
import eu.cloudnetservice.driver.service.ServiceTask;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

class CloudNetServiceStarterTest {

  private static final String TASK = "Donjon";

  private final CloudServiceProvider services = mock(CloudServiceProvider.class);
  private final CloudServiceFactory factory = mock(CloudServiceFactory.class);
  private final ServiceTaskProvider tasks = mock(ServiceTaskProvider.class);
  private final ServiceTask serviceTask = mock(ServiceTask.class);
  private final ServiceConfiguration configuration = mock(ServiceConfiguration.class);
  private final CloudNetServiceStarter starter = new CloudNetServiceStarter(
      NOPLogger.NOP_LOGGER, this.services, this.factory, this.tasks, task -> this.configuration);

  CloudNetServiceStarterTest() {
    when(this.tasks.serviceTaskAsync(TASK))
        .thenReturn(CompletableFuture.completedFuture(this.serviceTask));
  }

  private SpecificCloudServiceProvider provider(UUID id) {
    var provider = mock(SpecificCloudServiceProvider.class);
    when(provider.startAsync()).thenReturn(CompletableFuture.completedFuture(null));
    when(this.services.serviceProvider(id)).thenReturn(provider);
    return provider;
  }

  private ServiceInfoSnapshot instance(String name, UUID id, ServiceLifeCycle lifeCycle) {
    var serviceId = mock(ServiceId.class);
    when(serviceId.uniqueId()).thenReturn(id);
    var snapshot = mock(ServiceInfoSnapshot.class);
    when(snapshot.name()).thenReturn(name);
    when(snapshot.serviceId()).thenReturn(serviceId);
    when(snapshot.lifeCycle()).thenReturn(lifeCycle);
    return snapshot;
  }

  private void existing(ServiceInfoSnapshot... instances) {
    when(this.services.servicesByTaskAsync(TASK))
        .thenReturn(CompletableFuture.completedFuture(List.of(instances)));
  }

  private void factoryReturns(ServiceCreateResult result) {
    when(this.factory.createCloudServiceAsync(any()))
        .thenReturn(CompletableFuture.completedFuture(result));
  }

  @Test
  void aPreparedInstanceIsStartedAndNothingIsCreated() {
    // Arrange
    var id = UUID.randomUUID();
    var provider = this.provider(id);
    this.existing(this.instance("Donjon-1", id, ServiceLifeCycle.PREPARED));

    // Act
    var outcome = this.starter.ensureStarted(TASK).join();

    // Assert
    assertEquals(Outcome.STARTED, outcome);
    verify(provider).startAsync();
    verify(this.factory, never()).createCloudServiceAsync(any());
  }

  @Test
  void aRunningInstanceIsLeftAloneBecauseItIsAlreadyStarting() {
    // Arrange
    var id = UUID.randomUUID();
    this.existing(this.instance("Donjon-1", id, ServiceLifeCycle.RUNNING));

    // Act
    var outcome = this.starter.ensureStarted(TASK).join();

    // Assert
    assertEquals(Outcome.ALREADY_STARTING, outcome);
    verify(this.services, never()).serviceProvider(any(UUID.class));
    verify(this.factory, never()).createCloudServiceAsync(any());
  }

  @Test
  void withNoInstanceOneIsCreatedFromTheTaskThenStarted() {
    // Arrange
    var created = UUID.randomUUID();
    var provider = this.provider(created);
    this.existing();
    this.factoryReturns(ServiceCreateResult.created(
        this.instance("Donjon-1", created, ServiceLifeCycle.PREPARED)));

    // Act
    var outcome = this.starter.ensureStarted(TASK).join();

    // Assert
    assertEquals(Outcome.STARTED, outcome);
    verify(this.factory).createCloudServiceAsync(this.configuration);
    verify(provider).startAsync();
  }

  @Test
  void aFailedCreationIsAnOutcomeNotAnException() {
    this.existing();
    this.factoryReturns(ServiceCreateResult.FAILED);

    assertEquals(Outcome.FAILED, this.starter.ensureStarted(TASK).join());
  }

  @Test
  void aNodeAnsweringNothingCountsAsFailed() {
    // Arrange — "completed with null" is documented for a service the node could not create
    this.existing();
    this.factoryReturns(null);

    // Act + Assert
    assertEquals(Outcome.FAILED, this.starter.ensureStarted(TASK).join());
  }

  @Test
  void aDeferredCreationHasNoInstanceToWaitForAndIsNotWaitable() {
    // Arrange
    this.existing();
    this.factoryReturns(ServiceCreateResult.deferred(UUID.randomUUID()));

    // Act
    var outcome = this.starter.ensureStarted(TASK).join();

    // Assert
    assertEquals(Outcome.DEFERRED, outcome);
    verify(this.services, never()).serviceProvider(any(UUID.class));
  }

  @Test
  void aTaskCloudNetDoesNotKnowIsReportedAsSuch() {
    // Arrange
    this.existing();
    when(this.tasks.serviceTaskAsync(TASK)).thenReturn(CompletableFuture.completedFuture(null));

    // Act + Assert
    assertEquals(Outcome.UNKNOWN_TASK, this.starter.ensureStarted(TASK).join());
    verify(this.factory, never()).createCloudServiceAsync(any());
  }

  @Test
  void anUnreachableNodeIsAFailedOutcome() {
    when(this.services.servicesByTaskAsync(TASK))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("node unreachable")));

    assertEquals(Outcome.FAILED, this.starter.ensureStarted(TASK).join());
  }

  @Test
  void witnessWithoutTheCoordinatorEveryCallCreatesItsOwnInstance() {
    // Arrange — the node deduplicates nothing: five direct calls see "no instance" five times
    this.existing();
    var created = UUID.randomUUID();
    this.provider(created);
    this.factoryReturns(ServiceCreateResult.created(
        this.instance("Donjon-1", created, ServiceLifeCycle.PREPARED)));

    // Act
    for (int i = 0; i < 5; i++) {
      this.starter.ensureStarted(TASK).join();
    }

    // Assert
    verify(this.factory, times(5)).createCloudServiceAsync(any());
  }
}
