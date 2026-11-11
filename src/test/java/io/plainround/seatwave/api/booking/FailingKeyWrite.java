package io.plainround.seatwave.api.booking;

import java.sql.Connection;
import java.sql.SQLException;
import org.h2.api.Trigger;

/**
 * An H2 trigger that fails every write of a response into the key table: the crash "between the
 * database write and the key write". H2 creates the trigger itself from its class name, so the test
 * switches it on and off with CREATE and DROP TRIGGER instead of passing anything in.
 */
public final class FailingKeyWrite implements Trigger {

  /** The SQL that installs it. */
  public static final String INSTALL =
      "CREATE TRIGGER crash_before_key_write BEFORE UPDATE ON idempotency_keys FOR EACH ROW"
          + " CALL \"io.plainround.seatwave.api.booking.FailingKeyWrite\"";

  /** The SQL that removes it. */
  public static final String REMOVE = "DROP TRIGGER crash_before_key_write";

  @Override
  public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
    // Thrown after the seat UPDATE ran in the same transaction: the process "dies" here.
    throw new SQLException("simulated crash between the effect and the key write", "08006");
  }
}
