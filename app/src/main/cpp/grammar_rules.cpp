#include <string>

namespace QuantumGrammar {

    const std::string AGENT_JSON_SCHEMA = 
        "root        ::= object\n"
        "object      ::= \"{\\n\" space \"\\\"thought\\\": \" string \",\\n\" space \"\\\"tool\\\": \" tool-types \",\\n\" space \"\\\"params\\\": \" params-type \"\\n}\"\n"
        "tool-types  ::= \"\\\"fetch_battery_state\\\"\" | \"\\\"write_secure_log\\\"\" | \"\\\"done\\\"\"\n"
        "params-type ::= log-params | empty-params\n"
        "log-params  ::= \"{\\n\" space \"\\\"log_data\\\": \" string \"\\n\" space \"}\"\n"
        "empty-params::= \"{\\n\" space \"}\"\n"
        "string      ::= \"\\\"\" ([^\"]*) \"\\\"\"\n"
        "space       ::= \"  \"\n";

    const char* get_compiled_grammar() {
        return AGENT_JSON_SCHEMA.c_str();
    }
}
