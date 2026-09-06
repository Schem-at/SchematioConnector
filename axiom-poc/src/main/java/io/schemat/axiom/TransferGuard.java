package io.schemat.axiom;

/** Client-thread request ownership. A late result must never replace a newer clipboard. */
final class TransferGuard {
    private long generation;
    record Ticket(long generation, Object world, Object clipboard) {}
    Ticket begin(Object world, Object clipboard) { return new Ticket(++generation, world, clipboard); }
    void cancel() { generation++; }
    boolean isCurrent(Ticket ticket) { return ticket.generation == generation; }
    String rejection(Ticket ticket, Object world, Object clipboard, boolean placing, boolean permitted) {
        if (!isCurrent(ticket)) return "Request cancelled.";
        if (world == null || world != ticket.world) return "World changed. Load the build again when ready.";
        if (!permitted) return "This server does not allow importing builds into Axiom.";
        if (placing) return "Finish or cancel your Axiom placement first.";
        if (clipboard != ticket.clipboard) return "Your Axiom clipboard changed. Load again to replace it.";
        return null;
    }
}
