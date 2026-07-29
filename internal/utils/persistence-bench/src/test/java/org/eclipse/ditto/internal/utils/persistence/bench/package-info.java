/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
/**
 * Driver-level benchmark harness comparing the PostgreSQL and MongoDB core-persistence
 * backends (journal, snapshots, cleanup). Test-only; never runs in CI. See
 * {@code docs/superpowers/specs/2026-07-10-postgres-persistence-bench-design.md}.
 */
package org.eclipse.ditto.internal.utils.persistence.bench;
