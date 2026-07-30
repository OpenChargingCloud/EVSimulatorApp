package cloud.charging.v2g.tp

/** The ISO 15118 message sets a V2GTP frame can carry. Mirrors the C# `MessageSet`. */
enum class MessageSet {
    AppProtocol,
    Iso15118_2,
    Iso20CommonMessages,
    Iso20AC,
    Iso20DC,
    Iso20WPT,
    Iso20ACDP,
}
