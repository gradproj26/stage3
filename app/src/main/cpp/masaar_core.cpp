// masaar_core.cpp
#include "masaar_core.h"
#include <string>

// ================== Global state ==================
static std::string node_id = "unknown";

void setNodeId(const std::string& id) {
    node_id = id;
}

// رسالة DATA العادية اللي بنبعتها من الشات
std::string buildMessage(const std::string& payload, const std::string& dst) {
    std::string json = R"({"type":"DATA","src":")" + node_id +
                       R"(","dst":")" + dst +
                       R"(","payload":")" + payload +
                       R"(","seq":0})";
    return json;
}

// HELLO الخفيفة
std::string buildHelloMessage() {
    std::string json = R"({"type":"HELLO","src":")" + node_id +
                       R"(","version":"1.0"})";
    return json;
}

// ================== Tiny JSON helpers ==================

static std::string getJsonValue(const std::string& json,
                                const std::string& key) {
    // بندوّر على "key":"value"
    std::string pattern = "\"" + key + "\":\"";
    size_t pos = json.find(pattern);
    if (pos == std::string::npos) return "";

    pos += pattern.size();
    size_t end = json.find('"', pos);
    if (end == std::string::npos) return "";

    return json.substr(pos, end - pos);
}

static int getJsonInt(const std::string& json,
                      const std::string& key,
                      int defaultValue = 0) {
    std::string pattern = "\"" + key + "\":";
    size_t pos = json.find(pattern);
    if (pos == std::string::npos) return defaultValue;

    pos += pattern.size();
    size_t end = pos;
    while (end < json.size() && isdigit(json[end])) {
        ++end;
    }
    if (end == pos) return defaultValue;
    return std::stoi(json.substr(pos, end - pos));
}

// شكل الرسالة
ParsedMessage parseMessage(const std::string& jsonMsg) {
    ParsedMessage msg;
    msg.type    = getJsonValue(jsonMsg, "type");
    msg.src     = getJsonValue(jsonMsg, "src");
    msg.dst     = getJsonValue(jsonMsg, "dst");
    msg.payload = getJsonValue(jsonMsg, "payload");
    msg.seq     = getJsonInt(jsonMsg, "seq", 0);
    msg.valid   = !msg.type.empty();
    return msg;
}

// هل الـ payload باين إنه HELLO؟
static bool payloadLooksLikeHello(const std::string& payload) {
    return payload.find("\"type\":\"HELLO\"") != std::string::npos;
}

// ================== Routing decision ==================

std::string handleIncoming(const std::string& jsonMsg) {
    ParsedMessage msg = parseMessage(jsonMsg);
    if (!msg.valid) {
        return "DROP:INVALID";
    }

    // لو الرسالة نفسها HELLO (type=HELLO) ما ندخلهاش الشات
    if (msg.type == "HELLO") {
        return "DROP:HELLO_TOP";
    }

    if (msg.type == "DATA") {
        // لو في HELLO جوّه الـ payload (من نود قديمة) برضه ما نعرضهاش
        if (payloadLooksLikeHello(msg.payload)) {
            return "DROP:HELLO_IN_PAYLOAD";
        }

        // حالياً single-hop → أي DATA مستلمة نعملها DELIVER
        // (بعد كده نضيف منطق الـ dst / FORWARD)
        return "DELIVER:" + msg.payload;
    }

    // أنواع تانية مش فاهمينها دلوقتي
    return "DROP:UNKNOWN_TYPE";
}
