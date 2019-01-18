/*
 * Copyright (C) 2017-2018 Dremio Corporation
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
package com.dremio.exec.work.foreman;

import com.dremio.common.exceptions.ExecutionSetupException;

public class ForemanException extends ExecutionSetupException {
  private static final long serialVersionUID = -6943409010231014085L;
//  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ForemanException.class);

  public ForemanException() {
  }

  public ForemanException(final String message, final Throwable cause, final boolean enableSuppression,
                          final boolean writableStackTrace) {
    super(message, cause, enableSuppression, writableStackTrace);
  }

  public ForemanException(final String message, final Throwable cause) {
    super(message, cause);
  }

  public ForemanException(final String message) {
    super(message);
  }

  public ForemanException(final Throwable cause) {
    super(cause);
  }
}