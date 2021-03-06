package com.gangquan360.smartadmin.module.employee;

import com.baomidou.mybatisplus.plugins.Page;
import com.gangquan360.smartadmin.common.constant.JudgeEnum;
import com.gangquan360.smartadmin.common.domain.PageResultDTO;
import com.gangquan360.smartadmin.common.domain.ResponseDTO;
import com.gangquan360.smartadmin.constant.CommonConst;
import com.gangquan360.smartadmin.module.department.DepartmentDao;
import com.gangquan360.smartadmin.module.department.domain.entity.DepartmentEntity;
import com.gangquan360.smartadmin.module.employee.constant.EmployeeResponseCodeConst;
import com.gangquan360.smartadmin.module.employee.constant.EmployeeStatusEnum;
import com.gangquan360.smartadmin.module.employee.domain.bo.EmployeeBO;
import com.gangquan360.smartadmin.module.employee.domain.dto.*;
import com.gangquan360.smartadmin.module.employee.domain.entity.EmployeeEntity;
import com.gangquan360.smartadmin.module.employee.domain.vo.EmployeeVO;
import com.gangquan360.smartadmin.module.login.domain.RequestTokenBO;
import com.gangquan360.smartadmin.module.position.PositionService;
import com.gangquan360.smartadmin.module.position.domain.dto.PositionRelationAddDTO;
import com.gangquan360.smartadmin.module.position.domain.dto.PositionRelationResultDTO;
import com.gangquan360.smartadmin.module.privilege.service.PrivilegeEmployeeService;
import com.gangquan360.smartadmin.module.role.roleemployee.RoleEmployeeDao;
import com.gangquan360.smartadmin.module.role.roleemployee.domain.RoleEmployeeEntity;
import com.gangquan360.smartadmin.util.SmartBeanUtil;
import com.gangquan360.smartadmin.util.SmartDigestUtil;
import com.gangquan360.smartadmin.util.SmartPaginationUtil;
import com.gangquan360.smartadmin.util.SmartVerificationUtil;
import com.google.common.collect.Lists;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 员工管理
 *
 * @author lidoudou
 * @date 2017年12月21日上午11:54:52
 */
@Service
public class EmployeeService {

    private static final String RESET_PASSWORD = "123456";

    @Autowired
    private EmployeeDao employeeDao;

    @Autowired
    private DepartmentDao departmentDao;

    @Autowired
    private RoleEmployeeDao roleEmployeeDao;

    @Autowired
    private PositionService positionService;

    @Autowired
    private PrivilegeEmployeeService privilegeEmployeeService;

    /**
     * 员工基本信息的缓存
     */
    private static final ConcurrentHashMap<Long, EmployeeBO> employeeCache = new ConcurrentHashMap<>();


    public EmployeeBO getById(Long id) {
        EmployeeBO employeeBO = employeeCache.get(id);
        if (employeeBO == null) {
            EmployeeEntity employeeEntity = employeeDao.selectById(id);
            if (employeeEntity != null) {
                Boolean superman = privilegeEmployeeService.isSuperman(id);
                employeeBO = new EmployeeBO(employeeEntity, superman);
                employeeCache.put(employeeEntity.getId(), employeeBO);
            }
        }
        return employeeBO;
    }

    /**
     * 查询员工列表
     *
     * @param queryDTO
     * @return
     */
    public ResponseDTO<PageResultDTO<EmployeeVO>> selectEmployeeList(EmployeeQueryDTO queryDTO) {
        Page pageParam = SmartPaginationUtil.convert2PageQueryInfo(queryDTO);
        queryDTO.setIsDelete(JudgeEnum.NO.getValue());
        List<EmployeeDTO> empList = employeeDao.selectEmployeeList(pageParam, queryDTO);
        empList.stream().forEach(e -> {
            List<PositionRelationResultDTO> positionRelationList = positionService.queryPositionByEmployeeId(e.getId());
            if (CollectionUtils.isNotEmpty(positionRelationList)) {
                e.setPositionRelationList(positionRelationList);
                e.setPositionName(positionRelationList.stream().map(PositionRelationResultDTO::getPositionName).collect(Collectors.joining(",")));
            }
        });
        return ResponseDTO.succData(SmartPaginationUtil.convert2PageInfoDTO(pageParam, empList, EmployeeVO.class));
    }

    /**
     * 新增员工
     *
     * @param employeeAddDto
     * @param requestToken
     * @return
     */
    public ResponseDTO<String> addEmployee(EmployeeAddDTO employeeAddDto, RequestTokenBO requestToken) {
        EmployeeEntity entity = SmartBeanUtil.copy(employeeAddDto, EmployeeEntity.class);
        if (StringUtils.isNotEmpty(employeeAddDto.getIdCard())) {
            boolean checkResult = Pattern.matches(SmartVerificationUtil.ID_CARD, employeeAddDto.getIdCard());
            if (!checkResult) {
                return ResponseDTO.wrap(EmployeeResponseCodeConst.ID_CARD_ERROR);
            }
        }
        if (StringUtils.isNotEmpty(employeeAddDto.getBirthday())) {
            boolean checkResult = Pattern.matches(SmartVerificationUtil.DATE, employeeAddDto.getBirthday());
            if (!checkResult) {
                return ResponseDTO.wrap(EmployeeResponseCodeConst.BIRTHDAY_ERROR);
            }
        }
        //同名员工
        EmployeeDTO sameNameEmployee = employeeDao.getByLoginName(entity.getLoginName(), EmployeeStatusEnum.NORMAL.getValue());
        if (null != sameNameEmployee) {
            return ResponseDTO.wrap(EmployeeResponseCodeConst.LOGIN_NAME_EXISTS);
        }
        //同电话员工
        EmployeeDTO samePhoneEmployee = employeeDao.getByPhone(entity.getLoginName(), EmployeeStatusEnum.NORMAL.getValue());
        if (null != samePhoneEmployee) {
            return ResponseDTO.wrap(EmployeeResponseCodeConst.PHONE_EXISTS);
        }
        Long departmentId = entity.getDepartmentId();
        DepartmentEntity department = departmentDao.selectById(departmentId);
        if (department == null) {
            return ResponseDTO.wrap(EmployeeResponseCodeConst.DEPT_NOT_EXIST);
        }

        //如果没有密码  默认设置为123456
        String pwd = entity.getLoginPwd();
        if (StringUtils.isBlank(pwd)) {
            entity.setLoginPwd(SmartDigestUtil.encryptPassword(CommonConst.Password.SALT_FORMAT, RESET_PASSWORD));
        } else {
            entity.setLoginPwd(SmartDigestUtil.encryptPassword(CommonConst.Password.SALT_FORMAT, entity.getLoginPwd()));
        }

        entity.setCreateUser(requestToken.getRequestUserId());
        if (StringUtils.isEmpty(entity.getBirthday())) {
            entity.setBirthday(null);
        }
        employeeDao.insert(entity);

        PositionRelationAddDTO positionRelAddDTO = new PositionRelationAddDTO(employeeAddDto.getPositionIdList(), entity.getId());
        //存储所选岗位信息
        positionService.addPositionRelation(positionRelAddDTO);

        return ResponseDTO.succ();
    }

    /**
     * 更新禁用状态
     *
     * @param employeeId
     * @param status
     * @return
     */
    public ResponseDTO<String> updateStatus(Long employeeId, Integer status) {
        if (null == employeeId) {
            return ResponseDTO.wrap(EmployeeResponseCodeConst.EMP_NOT_EXISTS);
        }
        EmployeeBO entity = getById(employeeId);
        if (null == entity) {
            return ResponseDTO.wrap(EmployeeResponseCodeConst.EMP_NOT_EXISTS);
        }
        List<Long> empIds = Lists.newArrayList();
        empIds.add(employeeId);
        employeeDao.batchUpdateStatus(empIds, status);
        employeeCache.remove(employeeId);
        return ResponseDTO.succ();
    }

    /**
     * 批量更新员工状态
     *
     * @param batchUpdateStatusDTO
     * @return
     */
    public ResponseDTO<String> batchUpdateStatus(EmployeeBatchUpdateStatusDTO batchUpdateStatusDTO) {
        employeeDao.batchUpdateStatus(batchUpdateStatusDTO.getEmployeeIds(), batchUpdateStatusDTO.getStatus());
        if (batchUpdateStatusDTO.getEmployeeIds() != null) {
            batchUpdateStatusDTO.getEmployeeIds().forEach(e -> employeeCache.remove(e));
        }
        return ResponseDTO.succ();
    }

    /**
     * 更新员工
     *
     * @param updateDTO
     * @return
     */
    public ResponseDTO<String> updateEmployee(EmployeeUpdateDTO updateDTO) {
        Long employeeId = updateDTO.getId();
        EmployeeEntity employeeEntity = employeeDao.selectById(employeeId);
        if (null == employeeEntity) {
            return ResponseDTO.wrap(EmployeeResponseCodeConst.EMP_NOT_EXISTS);
        }
        if (StringUtils.isNotBlank(updateDTO.getIdCard())) {
            boolean checkResult = Pattern.matches(SmartVerificationUtil.ID_CARD, updateDTO.getIdCard());
            if (!checkResult) {
                return ResponseDTO.wrap(EmployeeResponseCodeConst.ID_CARD_ERROR);
            }
        }
        if (StringUtils.isNotEmpty(updateDTO.getBirthday())) {
            boolean checkResult = Pattern.matches(SmartVerificationUtil.DATE, updateDTO.getBirthday());
            if (!checkResult) {
                return ResponseDTO.wrap(EmployeeResponseCodeConst.BIRTHDAY_ERROR);
            }
        }
        Long departmentId = updateDTO.getDepartmentId();
        DepartmentEntity departmentEntity = departmentDao.selectById(departmentId);
        if (departmentEntity == null) {
            return ResponseDTO.wrap(EmployeeResponseCodeConst.DEPT_NOT_EXIST);
        }
        EmployeeDTO sameNameEmployee = employeeDao.getByLoginName(updateDTO.getLoginName(), EmployeeStatusEnum.NORMAL.getValue());
        if (null != sameNameEmployee && !sameNameEmployee.getId().equals(employeeId)) {
            return ResponseDTO.wrap(EmployeeResponseCodeConst.LOGIN_NAME_EXISTS);
        }
        EmployeeDTO samePhoneEmployee = employeeDao.getByPhone(updateDTO.getLoginName(), EmployeeStatusEnum.NORMAL.getValue());
        if (null != samePhoneEmployee && !samePhoneEmployee.getId().equals(employeeId)) {
            return ResponseDTO.wrap(EmployeeResponseCodeConst.PHONE_EXISTS);
        }
        String newPwd = updateDTO.getLoginPwd();
        if (!StringUtils.isBlank(newPwd)) {
            updateDTO.setLoginPwd(SmartDigestUtil.encryptPassword(CommonConst.Password.SALT_FORMAT, updateDTO.getLoginPwd()));
        } else {
            updateDTO.setLoginPwd(employeeEntity.getLoginPwd());
        }
        EmployeeEntity entity = SmartBeanUtil.copy(updateDTO, EmployeeEntity.class);
        entity.setUpdateTime(new Date());
        if (StringUtils.isEmpty(entity.getBirthday())) {
            entity.setBirthday(null);
        }
        if (CollectionUtils.isNotEmpty(updateDTO.getPositionIdList())) {
            //删除旧的关联关系 添加新的关联关系
            positionService.removePositionRelation(entity.getId());
            PositionRelationAddDTO positionRelAddDTO = new PositionRelationAddDTO(updateDTO.getPositionIdList(), entity.getId());
            positionService.addPositionRelation(positionRelAddDTO);
        }
        entity.setIsDisabled(employeeEntity.getIsDisabled());
        entity.setIsLeave(employeeEntity.getIsLeave());
        entity.setCreateUser(employeeEntity.getCreateUser());
        entity.setCreateTime(employeeEntity.getCreateTime());
        entity.setUpdateTime(new Date());
        employeeDao.updateById(entity);
        employeeCache.remove(employeeId);
        return ResponseDTO.succ();
    }

    /**
     * 删除员工
     *
     * @param employeeId 员工ID
     * @return
     */
    public ResponseDTO<String> deleteEmployeeById(Long employeeId) {
        EmployeeEntity employeeEntity = employeeDao.selectById(employeeId);
        if (null == employeeEntity) {
            return ResponseDTO.wrap(EmployeeResponseCodeConst.EMP_NOT_EXISTS);
        }
        //假删
        employeeEntity.setIsDelete(JudgeEnum.YES.getValue().longValue());
        employeeDao.updateById(employeeEntity);
        employeeCache.remove(employeeId);
        return ResponseDTO.succ();
    }

    /**
     * 更新用户角色
     *
     * @param updateRolesDTO
     * @return
     */
    public ResponseDTO<String> updateRoles(EmployeeUpdateRolesDTO updateRolesDTO) {
        roleEmployeeDao.deleteByEmployeeId(updateRolesDTO.getEmployeeId());
        if (CollectionUtils.isNotEmpty(updateRolesDTO.getRoleIds())) {
            List<RoleEmployeeEntity> roleEmployeeEntities = Lists.newArrayList();
            RoleEmployeeEntity roleEmployeeEntity;
            for (Long roleId : updateRolesDTO.getRoleIds()) {
                roleEmployeeEntity = new RoleEmployeeEntity();
                roleEmployeeEntity.setEmployeeId(updateRolesDTO.getEmployeeId());
                roleEmployeeEntity.setRoleId(roleId);
                roleEmployeeEntities.add(roleEmployeeEntity);
            }
            roleEmployeeDao.batchInsert(roleEmployeeEntities);
        }
        return ResponseDTO.succ();
    }

    /**
     * 更新密码
     *
     * @param updatePwdDTO
     * @param requestToken
     * @return
     */
    public ResponseDTO<String> updatePwd(EmployeeUpdatePwdDTO updatePwdDTO, RequestTokenBO requestToken) {
        Long employeeId = requestToken.getRequestUserId();
        EmployeeEntity employee = employeeDao.selectById(employeeId);
        if (employee == null) {
            return ResponseDTO.wrap(EmployeeResponseCodeConst.EMP_NOT_EXISTS);
        }
        if (!employee.getLoginPwd().equals(SmartDigestUtil.encryptPassword(CommonConst.Password.SALT_FORMAT, updatePwdDTO.getOldPwd()))) {
            return ResponseDTO.wrap(EmployeeResponseCodeConst.PASSWORD_ERROR);
        }
        employee.setLoginPwd(SmartDigestUtil.encryptPassword(CommonConst.Password.SALT_FORMAT, updatePwdDTO.getPwd()));
        employeeDao.updateById(employee);
        employeeCache.remove(employeeId);
        return ResponseDTO.succ();
    }

    public ResponseDTO<List<EmployeeVO>> getEmployeeByDeptId(Long departmentId) {
        List<EmployeeVO> list = employeeDao.getEmployeeIdByDeptId(departmentId);
        return ResponseDTO.succData(list);
    }

    /**
     * 重置密码
     *
     * @param employeeId
     * @return
     */
    public ResponseDTO resetPasswd(Integer employeeId) {
        String md5Password = SmartDigestUtil.encryptPassword(CommonConst.Password.SALT_FORMAT, RESET_PASSWORD);
        employeeDao.updatePassword(employeeId, md5Password);
        employeeCache.remove(employeeId);
        return ResponseDTO.succ();
    }

}
