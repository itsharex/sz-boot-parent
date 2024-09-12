package com.sz.mysql;

import cn.dev33.satoken.stp.StpUtil;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.constant.SqlConsts;
import com.mybatisflex.core.dialect.OperateType;
import com.mybatisflex.core.dialect.impl.CommonsDialectImpl;
import com.mybatisflex.core.query.*;
import com.sz.core.common.constant.GlobalConstant;
import com.sz.core.common.entity.ControlPermissions;
import com.sz.core.common.entity.LoginUser;
import com.sz.core.datascope.ControlThreadLocal;
import com.sz.core.datascope.SimpleDataScopeHelper;
import com.sz.core.util.SpringApplicationContextUtils;
import com.sz.core.util.StringUtils;
import com.sz.core.util.Utils;
import com.sz.security.core.util.LoginUtils;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 自定义方言 -- 数据权限
 *
 * @ClassName PermissionDialect
 * @Author sz
 * @Date 2024/6/17 10:36
 * @Version 1.0
 */
@Slf4j
public class SimplePermissionDialect extends CommonsDialectImpl {

    private final static String FIELD_CREATE_ID = "create_id";

    private final static String FIELD_DEPT_SCOPE = "dept_scope";

    @Override
    public void prepareAuth(QueryWrapper queryWrapper, OperateType operateType) {
        if (!SimpleDataScopeHelper.isDataScope() || !StpUtil.isLogin()) {
            super.prepareAuth(queryWrapper, operateType);
            return;
        }
        try {
            if (operateType != OperateType.SELECT) {
                super.prepareAuth(queryWrapper, operateType);
                return;
            }

            Class<?> tableClazz = SimpleDataScopeHelper.get();
            List<QueryTable> queryTables = CPI.getQueryTables(queryWrapper);
            List<QueryTable> joinTables = CPI.getJoinTables(queryWrapper);
            if (queryTables == null || queryTables.isEmpty()) {
                return;
            }
            LoginUser loginUser = LoginUtils.getLoginUser();
            if (loginUser == null || !ControlThreadLocal.hasLocal()) {
                super.prepareAuth(queryWrapper, operateType);
                return;
            }

            if (loginUser.getRoles().contains(GlobalConstant.SUPER_ROLE)) { // 管理员，可查看全部
                super.prepareAuth(queryWrapper, operateType);
                return;
            }
            processSubQueries(queryTables, operateType); // 处理子查询
            // 判断是否是isJoin
            boolean isJoin = CPI.getJoins(queryWrapper) != null && !CPI.getJoins(queryWrapper).isEmpty();
            Map<String, QueryTable> tableMap = buildTableMap(queryTables, isJoin, joinTables);
            if (tableMap == null) return;
            ControlPermissions permissions = ControlThreadLocal.get();
            String[] btnPermissions = permissions.getPermissions();
            Map<String, String> permissionMap = loginUser.getPermissionAndMenuIds();
            Map<String, String> ruleMap = loginUser.getRuleMap();
            String tableName = getTableName(tableClazz);
            QueryTable table = tableMap.get(tableName);
            if (table == null) return;

            // 根据权限规则处理查询
            String mode = permissions.getMode();
            String rule = determineRuleScope(btnPermissions, permissionMap, ruleMap, mode);
            String alias = Utils.isNotNull(table.getAlias()) ? table.getAlias() : table.getName();

            String logicMinUnit = SpringApplicationContextUtils.getBean(DataScopeProperties.class).getLogicMinUnit();
            switch (rule) {
                case "1006001": // 全部 - 放行
                    super.prepareAuth(queryWrapper, operateType);
                    return;
                case "1006002": // 本部门及以下
                    handleDeptScope(queryWrapper, loginUser.getDeptAndChildren(), logicMinUnit, alias, tableClazz);
                    break;
                case "1006003": // 仅本部门
                    handleDeptScope(queryWrapper, loginUser.getDepts(), logicMinUnit, alias, tableClazz);
                    break;
                case "1006004": // 仅本人
                default:
                    handlePersonalScope(queryWrapper, loginUser, table, tableClazz);
                    break;
            }

            // 自定义逻辑处理
            Map<String, Set<Long>> userRuleMap = loginUser.getUserRuleMap();
            if (!userRuleMap.isEmpty()) {
                Set<Long> relationIds = determineCustomRuleRelationIds(btnPermissions, permissionMap, userRuleMap, mode); // 自定义用户ID
                handleCustomUserRelation(queryWrapper, table, relationIds);
            }
            Map<String, Set<Long>> deptRuleMap = loginUser.getDeptRuleMap();
            if (!deptRuleMap.isEmpty()) {
                Set<Long> relationIds = determineCustomRuleRelationIds(btnPermissions, permissionMap, deptRuleMap, mode); // 自定义部门ID
                handleCustomDeptRelation(queryWrapper, relationIds, logicMinUnit, alias, tableClazz);
            }

        } catch (Exception e) {
            log.error(" PermissionDialect Exception :" + e.getMessage());
            e.printStackTrace();
        } finally {
            super.prepareAuth(queryWrapper, operateType);
        }
    }

    /**
     * 获取TableName：先根据Table注解获取value名，取不到再根据驼峰映射
     *
     * @param tableClazz
     * @return
     */
    private static String getTableName(Class<?> tableClazz) {
        String tableName = "";
        Table tableClazzAnnotation = tableClazz.getAnnotation(Table.class);
        if (tableClazzAnnotation == null) {
            String simpleName = tableClazz.getSimpleName(); // eg: TeacherStatics
            tableName = StringUtils.toSnakeCase(simpleName); // eg: teacher_statics
        } else {
            tableName = tableClazzAnnotation.value(); // eg: teacher_statics
        }
        return tableName;
    }

    private void processSubQueries(List<QueryTable> queryTables, OperateType operateType) {
        for (QueryTable queryTable : queryTables) {
            if (queryTable instanceof SelectQueryTable) {
                prepareAuth(((SelectQueryTable) queryTable).getQueryWrapper(), operateType);
            }
        }
    }

    private static Map<String, QueryTable> buildTableMap(List<QueryTable> queryTables, boolean isJoin, List<QueryTable> joinTables) {
        Map<String, QueryTable> tableMap = new HashMap<>();
        for (QueryTable queryTable : queryTables) {
            if (queryTable.getName() == null || ("").equals(queryTable.getName().trim())) {  // TODO 临时方案：如果name为空或空字符串直接return；等待官方修复。忽略非正常结构 queryTables ==[SELECT * FROM TABLE]
                return null;
            }
            tableMap.put(queryTable.getName(), queryTable);
        }
        if (isJoin) {
            for (QueryTable joinTable : joinTables) {
                if (joinTable.getName() != null && !("").equals(joinTable.getName().trim())) {
                    tableMap.put(joinTable.getName(), joinTable);
                }
            }
        }
        return tableMap;
    }

    /**
     * 根据权限和规则模式确定规则范围。
     *
     * @param permissionKeys      权限数组。
     * @param permissionAccessMap 权限到菜单ID的映射。
     * @param ruleScopeMap        菜单ID到规则范围的映射。
     * @param mode                规则模式，"or" 或 "and"。
     * @return 确定的scope规则范围。
     */
    private String determineRuleScope(String[] permissionKeys, Map<String, String> permissionAccessMap, Map<String, String> ruleScopeMap, String mode) {
        if (ruleScopeMap.isEmpty()) {
            return ""; // 如果规则映射为空，返回空字符串
        }

        // 根据权限键获取所有相关的菜单ID
        Set<String> menuIds = Arrays.stream(permissionKeys)
                .filter(permissionAccessMap::containsKey)
                .map(permissionAccessMap::get)
                .collect(Collectors.toSet());

        // 如果没有菜单ID，返回空字符串
        if (menuIds.isEmpty()) {
            return "";
        }

        // 如果没有找到任何菜单ID或只有一个，直接获取规则范围
        if (menuIds.size() == 1) {
            String menuId = menuIds.iterator().next(); // 获取集合中的唯一元素
            return ruleScopeMap.getOrDefault(menuId, ""); // 使用getOrDefault避免null
        }

        if (mode.isEmpty()) {
            return "";
        }
        System.out.println("mode ==" + mode);
        // 根据"or"或"and"模式确定规则范围
        return menuIds.stream()
                .map(menuId -> ruleScopeMap.getOrDefault(menuId, ""))
                .reduce((scope1, scope2) -> mode.equals("or") ? maxRuleScope(scope1, scope2) : minRuleScope(scope1, scope2))
                .orElse(""); // 如果没有找到任何规则范围，返回空字符串
    }

    /**
     * 自定义的 根据权限和规则模式确定规则范围。
     *
     * @param permissionKeys      权限数组
     * @param permissionAccessMap 权限到菜单ID的映射
     * @param ruleRelation        自定义规则Relation
     * @param mode                规则模式，"or" 或 "and"。
     * @return 确定的规则relationId范围
     */
    private Set<Long> determineCustomRuleRelationIds(String[] permissionKeys, Map<String, String> permissionAccessMap, Map<String, Set<Long>> ruleRelation, String mode) {
        Set<Long> relationId = new HashSet<>();
        if (ruleRelation.isEmpty()) {
            return relationId; // 如果规则映射为空，返回空字符串
        }

        // 根据权限键获取所有相关的菜单ID
        Set<String> menuIds = Arrays.stream(permissionKeys)
                .filter(permissionAccessMap::containsKey)
                .map(permissionAccessMap::get)
                .collect(Collectors.toSet());

        // 如果没有菜单ID，返回空字符串
        if (menuIds.isEmpty()) {
            return relationId;
        }

        // 如果没有找到任何菜单ID或只有一个，直接获取规则范围
        if (menuIds.size() == 1) {
            String menuId = menuIds.iterator().next(); // 获取集合中的唯一元素
            return ruleRelation.getOrDefault(menuId, relationId); // 使用getOrDefault避免null
        }

        if (mode.isEmpty()) {
            return relationId;
        }
        System.out.println("mode ==" + mode);
        // 根据"or"或"and"模式确定规则范围
        return menuIds.stream()
                .map(menuId -> ruleRelation.getOrDefault(menuId, relationId))
                .reduce((relation1, relation2) -> mode.equals("or") ? maxRelation(relation1, relation2) : minRelation(relation1, relation2))
                .orElse(relationId); // 如果没有找到任何规则范围，返回空字符串
    }

    /**
     * 字段有效性校验
     *
     * @param clazz
     * @param fieldName
     * @return
     */
    private boolean isFieldExists(Class<?> clazz, String fieldName) {
        try {
            // 尝试获取类中的字段
            Field field = clazz.getDeclaredField(fieldName);
            // 检查字段是否为null
            if (field != null) {
                return true;
            }
        } catch (NoSuchFieldException e) {
            log.error(" [DataScope]: Entity `{}` Filed `{}` not found.", clazz.getSimpleName(), fieldName);
        }
        return false;
    }

    /**
     * 部门及部门以下、仅本部门
     *
     * @param queryWrapper
     * @param depts
     * @param logicMinUnit
     * @param alias
     * @param tableClazz
     */
    private void handleDeptScope(QueryWrapper queryWrapper, Collection<Long> depts, String logicMinUnit, String alias, Class<?> tableClazz) {
        String field;
        Object context;
        if (depts.isEmpty()) {
            return;
        }
        if ("user".equals(logicMinUnit)) {
            field = FIELD_CREATE_ID;
            boolean exists = isFieldExists(tableClazz, StringUtils.toCamelCase(field));
            if (!exists) {
                return;
            }

            String sqlParams = depts.stream().map(String::valueOf).collect(Collectors.joining(", ", "(", ")"));
            String sql = " EXISTS ( SELECT 1 FROM `sys_user_dept` WHERE `sys_user_dept`.`dept_id` IN " + sqlParams + " AND `" + alias + "`.`" + field + "` = `sys_user_dept`.`user_id` )";
            String sqlSuper = " EXISTS ( SELECT 1 FROM `sys_user` WHERE `sys_user`.`id` = `" + alias + "`.`" + field + "` AND  `sys_user`.`user_tag_cd` = '1001002' AND `del_flag` = 'F' )";// 管理员Id查询
            context = CPI.getContext(queryWrapper, field);
            if (!Boolean.TRUE.equals(context)) {
                queryWrapper.where(sql);
                queryWrapper.or(sqlSuper);
                CPI.putContext(queryWrapper, field, true);
            }
        } else {
            field = FIELD_DEPT_SCOPE;
            context = CPI.getContext(queryWrapper, field);
            if (!Boolean.TRUE.equals(context)) {
                depts.forEach(dept -> {
                    String sql = "JSON_CONTAINS(" + alias + "." + field + ", '" + dept + "', '$')";
                    queryWrapper.or(sql);
                });
                CPI.putContext(queryWrapper, field, true);
            }
        }
    }

    /**
     * 仅本人
     *
     * @param queryWrapper
     * @param loginUser
     * @param table
     * @param tableClazz
     */
    private void handlePersonalScope(QueryWrapper queryWrapper, LoginUser loginUser, QueryTable table, Class<?> tableClazz) {
        String field = FIELD_CREATE_ID;
        boolean exists = isFieldExists(tableClazz, StringUtils.toCamelCase(field));
        if (!exists) {
            return;
        }
        QueryCondition queryCondition = QueryCondition.create(
                new QueryColumn(table.getSchema(), table.getName(), field, table.getAlias()),
                SqlConsts.EQUALS,
                loginUser.getUserInfo().getId()
        );
        Object context = CPI.getContext(queryWrapper, field);
        if (!Boolean.TRUE.equals(context)) {
            queryWrapper.where(queryCondition);
            CPI.putContext(queryWrapper, field, true);
        }
    }


    // 获取两个规则范围中的最大值 (1006001 ~ 1006004)
    private String maxRuleScope(String scope1, String scope2) {
        return Utils.getLongVal(scope1) >= Utils.getLongVal(scope2) ? scope1 : scope2;
    }

    // 获取两个规则范围中的最小值 (1006001 ~ 1006004)
    private String minRuleScope(String scope1, String scope2) {
        return Utils.getLongVal(scope1) <= Utils.getLongVal(scope2) ? scope1 : scope2;
    }

    // 获取两个集合的最大范围，合并。
    private Set<Long> maxRelation(Set<Long> relation1, Set<Long> relation2) {
        Set<Long> all = new HashSet<>();
        all.addAll(relation1);
        all.addAll(relation2);
        return all;
    }

    // 获取两个集合中的最小部分，取公共交集。
    private Set<Long> minRelation(Set<Long> relation1, Set<Long> relation2) {
        Set<Long> intersection = new HashSet<>(relation1); // 创建一个新集合，初始化为relation1的副本
        intersection.retainAll(relation2); // 保留两个集合的交集
        return intersection;
    }

    /**
     * 自定义-用户维度
     *
     * @param queryWrapper
     * @param table
     * @param customUserIds
     */
    private void handleCustomUserRelation(QueryWrapper queryWrapper, QueryTable table, Collection<Long> customUserIds) {
        if (customUserIds.isEmpty()) return;
        String field;
        field = FIELD_CREATE_ID;
        QueryCondition queryCondition = QueryCondition.create(
                new QueryColumn(table.getSchema(), table.getName(), field, table.getAlias()),
                SqlConsts.IN,
                customUserIds);
        Object contextUser = CPI.getContext(queryWrapper, field + "_1007002");
        if (!Boolean.TRUE.equals(contextUser)) {
            queryWrapper.or(queryCondition);
            CPI.putContext(queryWrapper, field + "_1007002", true);
        }
    }

    /**
     * 自定义-部门维度
     *
     * @param queryWrapper
     * @param depts
     * @param logicMinUnit
     * @param alias
     * @param tableClazz
     */
    private void handleCustomDeptRelation(QueryWrapper queryWrapper, Collection<Long> depts, String logicMinUnit, String alias, Class<?> tableClazz) {
        String field;
        Object context;
        if (depts.isEmpty()) {
            return;
        }
        if ("user".equals(logicMinUnit)) {
            field = FIELD_CREATE_ID;
            boolean exists = isFieldExists(tableClazz, StringUtils.toCamelCase(field));
            if (!exists) {
                return;
            }

            String sqlParams = depts.stream().map(String::valueOf).collect(Collectors.joining(", ", "(", ")"));
            String sql = " EXISTS ( SELECT 1 FROM `sys_user_dept` WHERE `sys_user_dept`.`dept_id` IN " + sqlParams + " AND `" + alias + "`.`" + field + "` = `sys_user_dept`.`user_id` )";
            context = CPI.getContext(queryWrapper, field + "_1007001");
            if (!Boolean.TRUE.equals(context)) {
                queryWrapper.or(sql);
                CPI.putContext(queryWrapper, field + "_1007001", true);
            }
        } else {
            field = FIELD_DEPT_SCOPE;
            context = CPI.getContext(queryWrapper, field + "_1007001");
            if (!Boolean.TRUE.equals(context)) {
                depts.forEach(dept -> {
                    String sql = "JSON_CONTAINS(" + alias + "." + field + ", '" + dept + "', '$')";
                    queryWrapper.or(sql);
                });
                CPI.putContext(queryWrapper, field + "_1007001", true);
            }
        }
    }


}
