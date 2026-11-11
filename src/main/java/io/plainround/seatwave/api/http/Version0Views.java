package io.plainround.seatwave.api.http;

/**
 * Module 1's JSON shapes, kept byte for byte for clients that have not moved to version 1. They are
 * not fixed here, even where version 1 is better (the error body, epoch milliseconds), because
 * fixing them would break those clients.
 */
final class Version0Views {

  private Version0Views() {}

  record EventVersion0(long eventId, String name, long startsAt) {}

  record SeatVersion0(String seat, String state) {}

  record HoldVersion0(String holdId, long expiresInSeconds) {}

  record BookingVersion0(String bookingId) {}

  record ErrorVersion0(String error) {}
}
