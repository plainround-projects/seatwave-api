package io.plainround.seatwave.api.catalog;

/** Why a catalog read was refused. */
public sealed interface CatalogProblem {

  /** There is no event with that id (404). */
  record EventNotFound() implements CatalogProblem {}

  /**
   * The cursor is not one this server made, or was made with other filters (400).
   *
   * @param detail what is wrong
   */
  record BadCursor(String detail) implements CatalogProblem {}
}
