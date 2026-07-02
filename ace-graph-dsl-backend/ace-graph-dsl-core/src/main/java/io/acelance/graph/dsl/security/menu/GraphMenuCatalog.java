package io.acelance.graph.dsl.security.menu;

import java.util.List;

/**
 * 菜单目录 SPI：定义设计器中可被权限控制的菜单 / 功能项清单。
 *
 * <p>默认实现 {@link DefaultGraphMenuCatalog} 提供 ace-graph-dsl 内置功能的标准目录。
 * 宿主应用如需新增自定义菜单项，可提供自己的 {@code GraphMenuCatalog} Bean 覆盖默认实现。</p>
 */
public interface GraphMenuCatalog {

    /**
     * 全部菜单 / 功能项元数据。
     *
     * @return 菜单描述列表
     */
    List<MenuDescriptor> items();
}
