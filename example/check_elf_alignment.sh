#!/bin/bash
progname="${0##*/}"
progname="${progname%.sh}"

# usage: ./check_elf_alignment.sh build/app/outputs/flutter-apk/app-debug.apk

cleanup_trap() {
  if [ -n "${tmp}" -a -d "${tmp}" ]; then
    rm -rf ${tmp}
  fi
  exit $1
}

usage() {
  echo "Host side script to check the ELF alignment of shared libraries."
  echo "Shared libraries are reported ALIGNED when their ELF regions are"
  echo "16 KB or 64 KB aligned. Otherwise they are reported as UNALIGNED."
  echo
  echo "Usage: ${progname} [input-path|input-APK|input-APEX]"
}

if [ ${#} -ne 1 ]; then
  usage
  exit
fi

case ${1} in
  --help | -h | -\?)
    usage
    exit
    ;;

  *)
    dir="${1}"
    ;;
esac

if ! [ -f "${dir}" -o -d "${dir}" ]; then
  echo "Invalid file: ${dir}" >&2
  exit 1
fi

if [[ "${dir}" == *.apk ]]; then
  trap 'cleanup_trap' EXIT

  echo
  echo "Recursively analyzing $dir"
  echo

  apk_file="${dir}"   # guarda o caminho do APK
  dir_filename=$(basename "${apk_file}")

  if { zipalign --help 2>&1 | grep -q "\-P <pagesize_kb>"; }; then
    echo "=== APK zip-alignment ==="
    zipalign -v -c -P 16 4 "${apk_file}" | egrep 'lib/arm64-v8a|lib/x86_64|Verification'
    echo "========================="
  else
    echo "NOTICE: Zip alignment check requires build-tools version 35.0.0-rc3 or higher."
    echo "  You can install the latest build-tools by running the below command"
    echo "  and updating your \$PATH:"
    echo
    echo "    sdkmanager \"build-tools;35.0.0-rc3\""
  fi

  if [[ "$(uname)" == "Darwin" ]]; then
    # macOS (BSD mktemp)
    tmp=$(mktemp -d -t "${dir_filename%.apk}_out")
  else
    # Linux (GNU mktemp)
    tmp=$(mktemp -d -t "${dir_filename%.apk}_out_XXXXX")
  fi

  unzip "${apk_file}" "lib/*" -d "${tmp}" >/dev/null 2>&1
  dir="${tmp}"
fi

if [[ "${dir}" == *.apex ]]; then
  trap 'cleanup_trap' EXIT

  echo
  echo "Recursively analyzing $dir"
  echo

  dir_filename=$(basename "${dir}")
  tmp=$(mktemp -d -t "${dir_filename%.apex}_out_XXXXX")
  deapexer extract "${dir}" "${tmp}" || { echo "Failed to deapex." && exit 1; }
  dir="${tmp}"
fi

echo "dir=$dir"
echo "dir_filename=$dir_filename"
echo "tmp=$tmp"
ls -l "$tmp"

RED="\e[31m"
GREEN="\e[32m"
ENDCOLOR="\e[0m"

unaligned_libs=()

echo
echo "=== ELF alignment ==="

# matches="$(find "${dir}" -type f)"
# IFS=$'\n'
# for match in $matches; do

#   # We could recursively call this script or rewrite it to though.
#   [[ "${match}" == *".apk" ]] && echo "WARNING: doesn't recursively inspect .apk file: ${match}"
#   [[ "${match}" == *".apex" ]] && echo "WARNING: doesn't recursively inspect .apex file: ${match}"

#   [[ $(file "${match}") == *"ELF"* ]] || continue

#   res="$($OBJDUMP -p "${match}" | grep LOAD | awk '{ print $NF }' | head -1)"
#   if [[ $res =~ 2\*\*(1[4-9]|[2-9][0-9]|[1-9][0-9]{2,}) ]]; then
#     echo -e "${match}: ${GREEN}ALIGNED${ENDCOLOR} ($res)"
#   else
#     echo -e "${match}: ${RED}UNALIGNED${ENDCOLOR} ($res)"
#     unaligned_libs+=("${match}")
#   fi
# done
# echo ${dir};
OBJDUMP=$(command -v gobjdump || command -v objdump)
while IFS= read -r match; do
  [[ "${match}" == *".apk" ]] && echo "WARNING: doesn't recursively inspect .apk file: ${match}"
  [[ "${match}" == *".apex" ]] && echo "WARNING: doesn't recursively inspect .apex file: ${match}"

  [[ $(file "${match}") == *"ELF"* ]] || continue

  res="$($OBJDUMP -p "${match}" | grep LOAD | awk '{ print $NF }' | head -1)"
  if [[ $res =~ 2\*\*(1[4-9]|[2-9][0-9]|[1-9][0-9]{2,}) ]]; then
    echo -e "${match}: ${GREEN}ALIGNED${ENDCOLOR} ($res)"
  else
    echo -e "${match}: ${RED}UNALIGNED${ENDCOLOR} ($res)"
    unaligned_libs+=("${match}")
  fi
done < <(find "${dir}" -type f)

count_unaligned=${#unaligned_libs[@]:-0}

if [ $count_unaligned -gt 0 ]; then
  echo -e "Found $count_unaligned unaligned libs (only arm64-v8a/x86_64 libs need to be aligned)."
elif [ -n "${dir_filename}" ]; then
  echo -e "ELF Verification Successful"
fi
echo "====================="