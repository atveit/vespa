// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "dense_fast_rename_function.h"
#include "dense_tensor.h"
#include "dense_tensor_view.h"
#include <vespa/eval/eval/value.h>
#include <vespa/eval/tensor/tensor.h>

namespace vespalib::tensor {

using CellsRef = DenseTensorView::CellsRef;
using eval::Value;
using eval::ValueType;
using eval::TensorFunction;
using eval::as;
using namespace eval::tensor_function;

namespace {

CellsRef getCellsRef(const eval::Value &value) {
    const DenseTensorView &denseTensor = static_cast<const DenseTensorView &>(value);
    return denseTensor.cellsRef();
}

void my_fast_rename_op(eval::InterpretedFunction::State &state, uint64_t param) {
    const ValueType *type = (const ValueType *)(param);
    CellsRef cells = getCellsRef(state.peek(0));
    state.pop_push(state.stash.create<DenseTensorView>(*type, cells));
}

bool is_concrete_dense_stable_rename(const ValueType &from_type, const ValueType &to_type,
                                     const std::vector<vespalib::string> &from,
                                     const std::vector<vespalib::string> &to)
{
    if (!from_type.is_dense() || from_type.is_abstract() ||
        !to_type.is_dense() || to_type.is_abstract() ||
        (from.size() != to.size()))
    {
        return false;
    }
    size_t npos = ValueType::Dimension::npos;
    for (size_t i = 0; i < from.size(); ++i) {
        size_t old_idx = from_type.dimension_index(from[i]);
        size_t new_idx = to_type.dimension_index(to[i]);
        if ((old_idx != new_idx) || (old_idx == npos)) {
            return false;
        }
    }
    return true;
}

} // namespace vespalib::tensor::<unnamed>


DenseFastRenameFunction::DenseFastRenameFunction(const eval::ValueType &result_type,
                                                 const eval::TensorFunction &child)
    : eval::tensor_function::Op1(result_type, child)
{
}

DenseFastRenameFunction::~DenseFastRenameFunction()
{
}

eval::InterpretedFunction::Instruction
DenseFastRenameFunction::compile_self(Stash &) const
{
    return eval::InterpretedFunction::Instruction(my_fast_rename_op, (uint64_t)&(result_type()));
}

const TensorFunction &
DenseFastRenameFunction::optimize(const eval::TensorFunction &expr, Stash &stash)
{
    if (auto rename = as<Rename>(expr)) {
        const ValueType &from_type = rename->child().result_type();
        const ValueType &to_type = expr.result_type();
        if (is_concrete_dense_stable_rename(from_type, to_type, rename->from(), rename->to())) {
            return stash.create<DenseFastRenameFunction>(to_type, rename->child());
        }
    }
    return expr;
}

} // namespace vespalib::tensor
