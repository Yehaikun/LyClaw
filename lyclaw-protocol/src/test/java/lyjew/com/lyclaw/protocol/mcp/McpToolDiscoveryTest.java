package lyjew.com.lyclaw.protocol.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class McpToolDiscoveryTest {

    private McpToolDiscovery discovery;
    private McpClient mockClient;

    @BeforeEach
    void setUp() {
        discovery = new McpToolDiscovery();
        mockClient = mock(McpClient.class);
    }

    @Nested
    @DisplayName("discoverAndRegister")
    class DiscoverAndRegister {

        @Test
        @DisplayName("Null McpClient returns empty list")
        void nullClientReturnsEmpty() {
            List<McpToolDescriptor> result = discovery.discoverAndRegister(null);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Discovers tools from all connected servers")
        void discoversTools() {
            when(mockClient.listConnectedServers()).thenReturn(Set.of("server1", "server2"));
            when(mockClient.discoverTools()).thenReturn(List.of(
                    McpToolDescriptor.builder().name("tool1").serverName("server1").description("d1").build(),
                    McpToolDescriptor.builder().name("tool2").serverName("server1").description("d2").build(),
                    McpToolDescriptor.builder().name("tool3").serverName("server2").description("d3").build()));

            List<McpToolDescriptor> result = discovery.discoverAndRegister(mockClient);

            assertThat(result).hasSize(3);
            assertThat(discovery.getToolCount()).isEqualTo(3);
            assertThat(discovery.getDiscoveredServers()).containsExactlyInAnyOrder("server1", "server2");
        }

        @Test
        @DisplayName("Tools index is rebuilt on each discover call")
        void indexRebuilt() {
            when(mockClient.listConnectedServers()).thenReturn(Set.of("s1"));
            when(mockClient.discoverTools()).thenReturn(List.of(
                    McpToolDescriptor.builder().name("t1").serverName("s1").build()));

            discovery.discoverAndRegister(mockClient);
            assertThat(discovery.getToolCount()).isEqualTo(1);

            when(mockClient.discoverTools()).thenReturn(List.of(
                    McpToolDescriptor.builder().name("t2").serverName("s1").build(),
                    McpToolDescriptor.builder().name("t3").serverName("s1").build()));
            discovery.discoverAndRegister(mockClient);

            assertThat(discovery.getToolCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("Tools with null serverName are keyed as 'unknown'")
        void nullServerNameAsUnknown() {
            when(mockClient.listConnectedServers()).thenReturn(Set.of());
            when(mockClient.discoverTools()).thenReturn(List.of(
                    McpToolDescriptor.builder().name("t1").serverName(null).build()));

            discovery.discoverAndRegister(mockClient);
            assertThat(discovery.getDiscoveredServers()).contains("unknown");
        }
    }

    @Nested
    @DisplayName("lookup")
    class Lookup {

        @Test
        @DisplayName("findToolByName returns tool by his simple name")
        void findToolByName() {
            when(mockClient.listConnectedServers()).thenReturn(Set.of("s1"));
            when(mockClient.discoverTools()).thenReturn(List.of(
                    McpToolDescriptor.builder().name("tool1").serverName("s1").build(),
                    McpToolDescriptor.builder().name("tool2").serverName("s1").build()));

            discovery.discoverAndRegister(mockClient);

            assertThat(discovery.findToolByName("tool1")).isNotNull();
            assertThat(discovery.findToolByName("tool2")).isNotNull();
            assertThat(discovery.findToolByName("unknown")).isNull();
        }

        @Test
        @DisplayName("findToolByQualifiedName returns fully-qualified tool")
        void findToolByQualifiedName() {
            when(mockClient.listConnectedServers()).thenReturn(Set.of("s1"));
            when(mockClient.discoverTools()).thenReturn(List.of(
                    McpToolDescriptor.builder().name("tool1").serverName("s1").build()));

            discovery.discoverAndRegister(mockClient);

            assertThat(discovery.findToolByQualifiedName("s1::tool1")).isNotNull();
            assertThat(discovery.findToolByQualifiedName("s2::tool1")).isNull();
        }

        @Test
        @DisplayName("getToolsForServer returns tools for a specific server")
        void getToolsForServer() {
            when(mockClient.listConnectedServers()).thenReturn(Set.of("s1", "s2"));
            when(mockClient.discoverTools()).thenReturn(List.of(
                    McpToolDescriptor.builder().name("t1").serverName("s1").build(),
                    McpToolDescriptor.builder().name("t2").serverName("s2").build()));

            discovery.discoverAndRegister(mockClient);

            assertThat(discovery.getToolsForServer("s1")).hasSize(1);
            assertThat(discovery.getToolsForServer("nonexistent")).isEmpty();
        }
    }

    @Test
    @DisplayName("clear removes all discovered tools")
    void clearRemovesAll() {
        when(mockClient.listConnectedServers()).thenReturn(Set.of("s1"));
        when(mockClient.discoverTools()).thenReturn(List.of(
                McpToolDescriptor.builder().name("t1").serverName("s1").build()));

        discovery.discoverAndRegister(mockClient);
        assertThat(discovery.getToolCount()).isEqualTo(1);

        discovery.clear();
        assertThat(discovery.getToolCount()).isEqualTo(0);
        assertThat(discovery.getDiscoveredServers()).isEmpty();
    }

    @Test
    @DisplayName("getAllDiscoveredTools returns a copy")
    void getAllDiscoveredToolsReturnsCopy() {
        when(mockClient.listConnectedServers()).thenReturn(Set.of("s1"));
        when(mockClient.discoverTools()).thenReturn(List.of(
                McpToolDescriptor.builder().name("t1").serverName("s1").build()));

        discovery.discoverAndRegister(mockClient);
        List<McpToolDescriptor> all = discovery.getAllDiscoveredTools();
        assertThat(all).hasSize(1);
    }
}
